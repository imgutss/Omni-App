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

const (
	// streamPendingMax bounds what one stream may queue towards the destination before its
	// writer applies backpressure to the application.
	streamPendingMax = 1 << 20
	// writeCoalesceWait is the breath the push loop takes after picking up a first chunk, so a
	// burst of small writes (TLS records, an HTTP request line by line) rides one door call
	// instead of paying a round-trip each. While a call is in flight the queue keeps filling,
	// which is where the real batching happens; this only smooths the start of a burst.
	writeCoalesceWait = 3 * time.Millisecond
	// closeFlushWait bounds how long Close waits for queued bytes to reach the door before it
	// gives up on them: long enough for a slow call, short enough that a dead door cannot pin
	// the connection handler.
	closeFlushWait = 15 * time.Second
)

// Mux carries many streams over one door session.
//
// Without it every SOCKS connection is a session of its own, with a poll of its own: twenty open
// connections cost twenty door calls every interval, which is what empties an Apps Script quota.
// With it the phone opens one session and many streams inside it, and pays one call per interval
// however many connections it holds. The exit node frames the answer by stream id; see guts/proto.
//
// One session is still one poll in flight, and a poll answer is bounded, so a single session's
// downstream is capped at roughly one answer per door round-trip however many streams share it.
// The proxy therefore runs a small pool of these (see Proxy.SetMuxSessions): a handful of
// sessions, each with its own poll in flight, multiplies that ceiling while the idle quota cost
// stays a handful of polls, not one per connection.
type Mux struct {
	door Door
	log  func(string, ...any)

	dialMu     sync.Mutex // held only while a session is being created, so it is created exactly once
	mu         sync.Mutex
	sid        string
	next       uint32
	dialing    int // opens in flight, counted as load so a burst spreads before streams register
	streams    map[uint32]*Stream
	failure    error
	closed     bool
	polling    bool
	pollingSid string
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

// Streams reports how many streams this session carries now.
func (m *Mux) Streams() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.streams)
}

// Load is Streams plus the opens still in flight: what a pool balances on, so six connections
// arriving in the same instant spread instead of all landing on the one mux that happens to
// still show zero streams.
func (m *Mux) Load() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.streams) + m.dialing
}

// Ready says this mux already holds a live session, so a new stream costs one open and its
// first bytes may ride along with it.
func (m *Mux) Ready() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sid != "" && m.failure == nil && !m.closed
}

// Failed says this mux ended for a transport reason and will not carry another stream; a pool
// replaces it instead of reusing it.
func (m *Mux) Failed() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.failure != nil || m.closed
}

// Dial opens one stream to host:port, and with the first stream the session that carries it.
func (m *Mux) Dial(ctx context.Context, host string, port int) (*Stream, error) {
	return m.DialData(ctx, host, port, nil)
}

// DialData is Dial with the stream's first bytes in hand. A v2 exit node writes them to the
// destination as part of the open, which saves the push round-trip that otherwise follows every
// open — on a path where a round-trip is a second or more, that is most of a page's connection
// cost. An older node ignores them, and they are then queued as the stream's first push, so the
// bytes are never lost either way.
//
// Once the session exists, opens run concurrently: a page opening six connections pays one
// round-trip of latency, not six in a row. Only the open that creates the session is serialized,
// so concurrent first streams cannot each create a session of their own.
func (m *Mux) DialData(ctx context.Context, host string, port int, initial []byte) (*Stream, error) {
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
	m.dialing++
	m.mu.Unlock()
	registered := false
	defer func() {
		if !registered {
			m.mu.Lock()
			m.dialing--
			m.mu.Unlock()
		}
	}()

	// Only the first chunk fits in an open; the rest rides the ordinary push queue.
	first, rest := initial, []byte(nil)
	if len(first) > proto.MaxPushBytes {
		first, rest = first[:proto.MaxPushBytes], first[proto.MaxPushBytes:]
	}

	var resp proto.Response
	var sid string
	// A session-creating open holds dialMu until its stream is registered: released earlier,
	// the next creator would still see no session and create a second one — or, worse, open
	// into a session this dial is about to refuse as unmultiplexable and close. Opens into an
	// existing session never touch the lock, which is where the concurrency lives.
	locked := false
	unlockDial := func() {
		if locked {
			m.dialMu.Unlock()
			locked = false
		}
	}
	defer unlockDial()
	for attempt := 0; ; attempt++ {
		m.mu.Lock()
		sid = m.sid
		m.mu.Unlock()

		if sid == "" && !locked {
			m.dialMu.Lock()
			locked = true
			// Another stream may have created the session while this one waited; the lock is
			// then simply carried through this open and released after registration.
			m.mu.Lock()
			sid = m.sid
			m.mu.Unlock()
		}
		var err error
		resp, err = m.open(ctx, sid, id, host, port, first)
		if err != nil {
			return nil, err
		}
		if resp.OK || sid == "" || resp.Error != "no session" || attempt > 0 {
			break
		}
		// The exit node forgot the session (an idle sweep, a restart). Opening this stream in
		// a new session is honest: the streams of the lost session end, this one lives.
		m.log("mux: the exit node lost session %s, opening a new one", sid)
		m.mu.Lock()
		if m.sid == sid {
			m.sid = ""
		}
		m.mu.Unlock()
	}
	if resp.OK && resp.Sid != "" && resp.Info != "" &&
		resp.Info != proto.MuxCapability && resp.Info != proto.MuxCapabilityV2 {
		m.closeSession(resp.Sid)
		m.mu.Lock()
		if m.sid == resp.Sid {
			m.sid = ""
		}
		m.mu.Unlock()
		return nil, ErrNoMux
	}
	if resp.OK && sid == "" && resp.Info != proto.MuxCapability && resp.Info != proto.MuxCapabilityV2 {
		// Asked for a new multiplexed session and got one nobody can multiplex over. Ending it is
		// the honest thing: the caller falls back instead of decoding raw bytes as frames.
		m.closeSession(resp.Sid)
		m.mu.Lock()
		if m.sid == resp.Sid {
			m.sid = ""
		}
		m.mu.Unlock()
		return nil, ErrNoMux
	}
	if !resp.OK || resp.Sid == "" {
		if resp.Error == "" {
			return nil, errors.New("guts: open refused")
		}
		return nil, errors.New("guts: " + resp.Error)
	}

	st := newStream(m, id, resp.Sid)
	if len(first) > 0 && resp.Info != proto.MuxCapabilityV2 {
		st.enqueue(first)
	}
	if len(rest) > 0 {
		st.enqueue(rest)
	}

	m.mu.Lock()
	if m.failure != nil {
		err := m.failure
		m.mu.Unlock()
		return nil, err
	}
	if m.sid == "" || m.sid == resp.Sid {
		m.sid = resp.Sid
	}
	if resp.Info == proto.MuxCapabilityV2 {
	}
	m.streams[id] = st
	m.dialing--
	registered = true
	startPolling := !m.polling || m.pollingSid != resp.Sid
	m.polling = true
	m.pollingSid = resp.Sid
	m.mu.Unlock()
	unlockDial()

	go st.pushLoop()
	if startPolling {
		go m.pollLoop(resp.Sid)
	}
	return st, nil
}

// open asks the exit node for one stream of a session, or for a new session when sid is empty.
func (m *Mux) open(ctx context.Context, sid string, id uint32, host string, port int, first []byte) (proto.Response, error) {
	req := proto.Request{Op: proto.OpOpen, Sid: sid, S: id, MU: true, Host: host, Port: port}
	if len(first) > 0 {
		req.D = base64.StdEncoding.EncodeToString(first)
	}
	return m.door.Call(ctx, req)
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

// pollLoop is the one poll of its session: it demultiplexes every stream's frames.
func (m *Mux) pollLoop(sid string) {
	quiet := 0
	for {
		m.mu.Lock()
		current := m.sid == sid
		closed := m.closed
		streams := 0
		for _, st := range m.streams {
			if st.sid == sid {
				streams++
			}
		}
		m.mu.Unlock()
		if closed || !current {
			return
		}
		if streams == 0 {
			// Nothing to carry: wait for the next stream instead of spending door calls.
			m.mu.Lock()
			if m.pollingSid == sid {
				m.polling = false
			}
			m.mu.Unlock()
			return
		}

		wait := proto.DefaultPollMs
		if quiet >= 2 {
			wait = proto.IdlePollMs
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(wait)*time.Millisecond+30*time.Second)
		resp, err := m.door.Call(ctx, proto.Request{
			Op: proto.OpPoll, Sid: sid, MU: true, N: proto.MaxPollMax, T: wait,
		})
		cancel()
		if err != nil {
			m.fail(err)
			return
		}
		if !resp.OK {
			if resp.Error == "no session" {
				m.loseSession(sid)
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
// opens a new one. A session that has already been replaced is left alone.
func (m *Mux) loseSession(sid string) {
	m.log("mux: the exit node no longer holds session %s", sid)
	m.mu.Lock()
	if m.sid == sid {
		m.sid = ""
	}
	if m.pollingSid == sid {
		m.polling = false
	}
	streams := make([]*Stream, 0)
	for id, st := range m.streams {
		if st.sid == sid {
			streams = append(streams, st)
			delete(m.streams, id)
		}
	}
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
	sid string

	mu    sync.Mutex
	cond  *sync.Cond
	queue [][]byte
	ended bool
	err   error

	wmu         sync.Mutex
	wcond       *sync.Cond
	pending     [][]byte
	pendingLen  int
	writeClosed bool
	writeErr    error
	closeSent   bool

	ctx        context.Context
	cancel     context.CancelFunc
	writerDone chan struct{}
	closeOnce  sync.Once
}

func newStream(m *Mux, id uint32, sid string) *Stream {
	ctx, cancel := context.WithCancel(context.Background())
	st := &Stream{mux: m, id: id, sid: sid, ctx: ctx, cancel: cancel, writerDone: make(chan struct{})}
	st.cond = sync.NewCond(&st.mu)
	st.wcond = sync.NewCond(&st.wmu)
	return st
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

// enqueue appends a copy of p to the push queue without backpressure; it serves the first
// bytes an older exit node did not take with the open, which must not block the dial.
func (st *Stream) enqueue(p []byte) {
	data := make([]byte, len(p))
	copy(data, p)
	st.wmu.Lock()
	st.pending = append(st.pending, data)
	st.pendingLen += len(data)
	st.wcond.Broadcast()
	st.wmu.Unlock()
}

// Write queues towards the destination and returns at once.
//
// It used to spend a whole door round-trip per chunk, synchronously: an application writing a
// TLS flight or an upload was throttled to one chunk per second-plus, and every small write
// burned quota of its own. The push loop below coalesces the queue into the largest pushes the
// protocol allows, so upstream throughput stops depending on how the application slices its
// writes. The queue is bounded, so a fast writer behind a slow door still sees backpressure —
// just at a megabyte, not at one chunk.
func (st *Stream) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		// Queue in protocol-sized pieces: one caller-sized write larger than the whole
		// pending bound could otherwise wait forever for room that can never exist.
		n := len(p)
		if n > proto.MaxPushBytes {
			n = proto.MaxPushBytes
		}
		data := make([]byte, n)
		copy(data, p[:n])

		m := st.mux
		m.mu.Lock()
		failure := m.failure
		m.mu.Unlock()
		if failure != nil {
			return total, failure
		}

		st.wmu.Lock()
		for st.writeErr == nil && !st.writeClosed && st.pendingLen+len(data) > streamPendingMax {
			st.wcond.Wait()
		}
		if st.writeErr != nil {
			err := st.writeErr
			st.wmu.Unlock()
			return total, err
		}
		if st.writeClosed {
			st.wmu.Unlock()
			return total, errors.New("guts: the stream is closed")
		}
		st.pending = append(st.pending, data)
		st.pendingLen += len(data)
		st.wcond.Broadcast()
		st.wmu.Unlock()

		total += n
		p = p[n:]
	}
	return total, nil
}

// pushLoop drains the queue towards the exit node, then sends the stream's close.
func (st *Stream) pushLoop() {
	defer close(st.writerDone)
	for {
		st.wmu.Lock()
		for len(st.pending) == 0 && !st.writeClosed && st.writeErr == nil {
			st.wcond.Wait()
		}
		if st.writeErr != nil {
			st.wmu.Unlock()
			return
		}
		if len(st.pending) == 0 {
			st.wmu.Unlock()
			st.sendClose()
			return
		}
		batch := st.takePendingLocked(proto.MaxPushBytes)
		st.wmu.Unlock()

		// One breath for the rest of a burst to land, then whatever queued joins this push.
		if len(batch) < proto.MaxPushBytes {
			time.Sleep(writeCoalesceWait)
			st.wmu.Lock()
			batch = append(batch, st.takePendingLocked(proto.MaxPushBytes-len(batch))...)
			st.wmu.Unlock()
		}
		if err := st.push(batch); err != nil {
			st.wmu.Lock()
			if st.writeErr == nil {
				st.writeErr = err
			}
			st.wcond.Broadcast()
			st.wmu.Unlock()
			st.end(err)
			return
		}
	}
}

// takePendingLocked removes up to limit queued bytes. Callers hold wmu.
func (st *Stream) takePendingLocked(limit int) []byte {
	var batch []byte
	for len(st.pending) > 0 && len(batch) < limit {
		chunk := st.pending[0]
		room := limit - len(batch)
		if len(chunk) <= room {
			batch = append(batch, chunk...)
			st.pending = st.pending[1:]
			st.pendingLen -= len(chunk)
		} else {
			batch = append(batch, chunk[:room]...)
			st.pending[0] = chunk[room:]
			st.pendingLen -= room
		}
	}
	if len(batch) > 0 {
		st.wcond.Broadcast() // room for the writers again
	}
	return batch
}

func (st *Stream) push(data []byte) error {
	for len(data) > 0 {
		n := len(data)
		if n > proto.MaxPushBytes {
			n = proto.MaxPushBytes
		}
		ctx, cancel := context.WithTimeout(st.ctx, 90*time.Second)
		resp, err := st.mux.door.Call(ctx, proto.Request{
			Op: proto.OpPush, Sid: st.sid, S: st.id, MU: true,
			D: base64.StdEncoding.EncodeToString(data[:n]),
		})
		cancel()
		if err != nil {
			return err
		}
		if !resp.OK {
			return errors.New("guts: " + resp.Error)
		}
		data = data[n:]
	}
	return nil
}

// sendClose tells the exit node this stream is over, once.
func (st *Stream) sendClose() {
	st.wmu.Lock()
	if st.closeSent {
		st.wmu.Unlock()
		return
	}
	st.closeSent = true
	st.wmu.Unlock()
	if st.sid == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = st.mux.door.Call(ctx, proto.Request{Op: proto.OpClose, Sid: st.sid, S: st.id, MU: true})
}

// Close ends this stream at the exit node, leaving the session and its other streams running.
// Queued bytes are flushed first, so closing right after writing does not lose the tail.
func (st *Stream) Close() error {
	st.closeOnce.Do(func() {
		st.mu.Lock()
		st.ended = true
		st.cond.Broadcast()
		st.mu.Unlock()

		st.wmu.Lock()
		st.writeClosed = true
		st.wcond.Broadcast()
		st.wmu.Unlock()

		st.mux.forget(st.id)
		select {
		case <-st.writerDone:
		case <-time.After(closeFlushWait):
			st.cancel()
			<-st.writerDone
		}
		st.sendClose()
	})
	return nil
}

func (st *Stream) end(err error) {
	st.mu.Lock()
	st.ended = true
	if st.err == nil {
		st.err = err
	}
	st.cond.Broadcast()
	st.mu.Unlock()
	if err != nil {
		// A broken session will never take the queued bytes; let writers see it now rather
		// than after a timeout.
		st.wmu.Lock()
		if st.writeErr == nil {
			st.writeErr = err
		}
		st.wcond.Broadcast()
		st.wmu.Unlock()
	}
}
