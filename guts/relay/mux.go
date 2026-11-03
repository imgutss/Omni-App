package relay

import (
	"context"
	"encoding/base64"
	"errors"
	"io"
	"sync"
	"time"

	"guts/proto"
)

// ErrNoMux says the exit node predates multiplexing: the phone falls back to one session per
// connection, and pays one door call per connection, which is what it then is.
var ErrNoMux = errors.New("guts: the exit node does not multiplex")

// Mux carries many streams over one door session.
//
// Without it every SOCKS connection is a session of its own, with a poll of its own: twenty open
// connections cost twenty door calls every interval, which is what empties an Apps Script quota.
// With it the phone opens one session and many streams inside it, and pays one call per interval
// however many connections it holds. The exit node frames the answer by stream id; see guts/proto.
type Mux struct {
	door Door
	log  func(string, ...any)

	dialMu  sync.Mutex // one stream opens at a time, so the session is created exactly once
	mu      sync.Mutex
	sid     string
	next    uint32
	streams map[uint32]*Stream
	failure error
	closed  bool
	polling bool
}

// NewMux builds a multiplexer over one door.
func NewMux(door Door, logf func(string, ...any)) *Mux {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Mux{door: door, log: logf, streams: map[uint32]*Stream{}}
}

// Session reports the session id in use, empty before the first stream.
func (m *Mux) Session() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sid
}

// Dial opens one stream to host:port, and with the first stream the session that carries it.
//
// One stream opens at a time: concurrent opens otherwise each see an empty session id and each
// create a session of their own, which is the very cost this type exists to avoid.
func (m *Mux) Dial(ctx context.Context, host string, port int) (*Stream, error) {
	m.dialMu.Lock()
	defer m.dialMu.Unlock()

	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, errors.New("guts: the session is closed")
	}
	if m.failure != nil {
		err := m.failure
		m.mu.Unlock()
		return nil, err
	}
	m.next++
	id := m.next
	sid := m.sid
	m.mu.Unlock()

	resp, err := m.open(ctx, sid, id, host, port)
	if err == nil && !resp.OK && sid != "" && resp.Error == "no session" {
		// The exit node forgot the session (an idle sweep, a restart). Opening a stream in a new
		// session is honest: the streams of the lost session end, this one lives.
		m.log("mux: the exit node lost session %s, opening a new one", sid)
		m.mu.Lock()
		if m.sid == sid {
			m.sid = ""
		}
		m.mu.Unlock()
		sid = ""
		resp, err = m.open(ctx, "", id, host, port)
	}
	if err != nil {
		return nil, err
	}
	if resp.OK && sid == "" && resp.Info != proto.MuxCapability {
		// Asked for a new multiplexed session and got one nobody can multiplex over. Ending it is
		// the honest thing: the caller falls back instead of decoding raw bytes as frames.
		m.closeSession(resp.Sid)
		return nil, ErrNoMux
	}
	if !resp.OK || resp.Sid == "" {
		if resp.Error == "" {
			return nil, errors.New("guts: open refused")
		}
		return nil, errors.New("guts: " + resp.Error)
	}

	st := &Stream{mux: m, id: id}
	st.cond = sync.NewCond(&st.mu)

	m.mu.Lock()
	if m.failure != nil {
		err := m.failure
		m.mu.Unlock()
		return nil, err
	}
	if resp.Sid != sid || sid == "" {
		m.sid = resp.Sid
	}
	m.streams[id] = st
	startPolling := !m.polling
	m.polling = true
	m.mu.Unlock()

	if startPolling {
		go m.pollLoop()
	}
	return st, nil
}

// open asks the exit node for one stream of a session, or for a new session when sid is empty.
func (m *Mux) open(ctx context.Context, sid string, id uint32, host string, port int) (proto.Response, error) {
	return m.door.Call(ctx, proto.Request{
		Op: proto.OpOpen, Sid: sid, S: id, MU: true, Host: host, Port: port,
	})
}

// closeSession ends a session at the exit node without touching the streams of this mux.
func (m *Mux) closeSession(sid string) {
	if sid == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = m.door.Call(ctx, proto.Request{Op: proto.OpClose, Sid: sid, MU: true})
}

// pollLoop is the one poll of the whole session: it demultiplexes every stream's frames.
func (m *Mux) pollLoop() {
	quiet := 0
	for {
		m.mu.Lock()
		sid := m.sid
		closed := m.closed
		streams := len(m.streams)
		m.mu.Unlock()
		if closed || sid == "" {
			return
		}
		if streams == 0 {
			// Nothing to carry: wait for the next stream instead of spending door calls.
			m.mu.Lock()
			m.polling = false
			m.mu.Unlock()
			return
		}

		wait := proto.DefaultPollMs
		if quiet >= 2 {
			wait = proto.IdlePollMs
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(wait)*time.Millisecond+30*time.Second)
		resp, err := m.door.Call(ctx, proto.Request{
			Op: proto.OpPoll, Sid: sid, MU: true, N: proto.DefaultPollMax, T: wait,
		})
		cancel()
		if err != nil {
			m.fail(err)
			return
		}
		if !resp.OK {
			if resp.Error == "no session" {
				m.loseSession()
				return
			}
			m.fail(errors.New("guts: " + resp.Error))
			return
		}
		if resp.EOF {
			m.log("mux: the exit node ended the session")
			// Every stream of the session ended at the exit node.
			m.fail(io.EOF)
			return
		}
		if resp.D == "" {
			quiet++
			continue
		}
		body, err := base64.StdEncoding.DecodeString(resp.D)
		if err != nil {
			m.fail(err)
			return
		}
		frames, err := proto.DecodeFrames(body)
		if err != nil {
			m.fail(err)
			return
		}
		quiet = 0
		for _, frame := range frames {
			m.deliver(frame)
		}
	}
}

func (m *Mux) deliver(frame proto.Frame) {
	m.mu.Lock()
	st := m.streams[frame.S]
	m.mu.Unlock()
	if st == nil {
		// A stream the phone closed; its bytes are dropped, which the exit node's socket has
		// already seen end.
		return
	}
	m.log("mux: frame s=%d bytes=%d eof=%v", frame.S, len(frame.D), frame.EOF)
	if len(frame.D) > 0 {
		st.receive(frame.D)
	}
	if frame.EOF {
		st.end(nil)
		m.forget(frame.S)
	}
}

func (m *Mux) forget(id uint32) {
	m.mu.Lock()
	delete(m.streams, id)
	m.mu.Unlock()
}

// loseSession forgets a session the exit node no longer holds: its streams end, and the next Dial
// opens a new one.
func (m *Mux) loseSession() {
	m.log("mux: the exit node no longer holds this session")
	m.mu.Lock()
	m.sid = ""
	m.polling = false
	streams := make([]*Stream, 0, len(m.streams))
	for _, st := range m.streams {
		streams = append(streams, st)
	}
	m.streams = map[uint32]*Stream{}
	m.mu.Unlock()
	for _, st := range streams {
		st.end(errors.New("guts: the session was lost"))
	}
	if len(streams) > 0 {
		// Silent: the streams carry their own error to the app.
		m.log("mux: %d streams ended with the lost session", len(streams))
	}
}

// fail ends every stream of the session with err.
func (m *Mux) fail(err error) {
	m.log("mux: session ended: %v", err)
	m.mu.Lock()
	if m.failure != nil {
		m.mu.Unlock()
		return
	}
	m.failure = err
	m.polling = false
	streams := make([]*Stream, 0, len(m.streams))
	for _, st := range m.streams {
		streams = append(streams, st)
	}
	m.streams = map[uint32]*Stream{}
	m.mu.Unlock()
	for _, st := range streams {
		st.end(err)
	}
}

// Close ends the session and every stream in it.
func (m *Mux) Close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	sid := m.sid
	m.mu.Unlock()
	m.fail(errors.New("guts: the session is closed"))
	if sid == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = m.door.Call(ctx, proto.Request{Op: proto.OpClose, Sid: sid, MU: true})
}

// Stream is one connection carried inside a multiplexed session.
type Stream struct {
	mux *Mux
	id  uint32

	mu    sync.Mutex
	cond  *sync.Cond
	queue [][]byte
	ended bool
	err   error

	writeMu sync.Mutex
	closed  bool
}

func (st *Stream) receive(data []byte) {
	st.mu.Lock()
	st.queue = append(st.queue, data)
	st.cond.Broadcast()
	st.mu.Unlock()
}

// Read returns what the destination sent, and io.EOF once it ended.
func (st *Stream) Read(p []byte) (int, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	for len(st.queue) == 0 && !st.ended {
		st.cond.Wait()
	}
	if len(st.queue) == 0 {
		if st.err != nil {
			return 0, st.err
		}
		return 0, io.EOF
	}
	data := st.queue[0]
	n := copy(p, data)
	if n < len(data) {
		st.queue[0] = data[n:]
	} else {
		st.queue = st.queue[1:]
	}
	return n, nil
}

// Write sends towards the destination, one push per chunk.
func (st *Stream) Write(p []byte) (int, error) {
	st.writeMu.Lock()
	defer st.writeMu.Unlock()
	if st.closed {
		return 0, errors.New("guts: the stream is closed")
	}
	m := st.mux
	m.mu.Lock()
	sid := m.sid
	failure := m.failure
	m.mu.Unlock()
	if failure != nil {
		return 0, failure
	}
	if sid == "" {
		return 0, errors.New("guts: no session")
	}
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > proto.MaxPushBytes {
			n = proto.MaxPushBytes
		}
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		resp, err := m.door.Call(ctx, proto.Request{
			Op: proto.OpPush, Sid: sid, S: st.id, MU: true,
			D: base64.StdEncoding.EncodeToString(p[:n]),
		})
		cancel()
		if err != nil {
			return total, err
		}
		if !resp.OK {
			return total, errors.New("guts: " + resp.Error)
		}
		total += n
		p = p[n:]
	}
	return total, nil
}

// Close ends this stream at the exit node, leaving the session and its other streams running.
func (st *Stream) Close() error {
	st.mu.Lock()
	st.ended = true
	st.cond.Broadcast()
	st.mu.Unlock()

	st.writeMu.Lock()
	alreadyClosed := st.closed
	st.closed = true
	st.writeMu.Unlock()
	if alreadyClosed {
		return nil
	}
	m := st.mux
	m.forget(st.id)
	m.mu.Lock()
	sid := m.sid
	m.mu.Unlock()
	if sid == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = m.door.Call(ctx, proto.Request{Op: proto.OpClose, Sid: sid, S: st.id, MU: true})
	return nil
}

func (st *Stream) end(err error) {
	st.mu.Lock()
	if !st.ended {
		st.ended = true
	}
	if st.err == nil {
		st.err = err
	}
	st.cond.Broadcast()
	st.mu.Unlock()
}
