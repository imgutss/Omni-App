// Multiplexed sessions: one door session carries many streams, one per connection the phone leans
// on, so the phone pays one poll per interval instead of one per connection.
//
// Nothing about the door changes. A mux session is an ordinary session id; the difference is that
// its push names a stream, and its poll answers a body of frames — a stream id, a length and that
// stream's bytes — which is where the quota saving comes from: a hundred open connections still
// cost one call every few seconds, not a hundred.
package exitnode

import (
	"bytes"
	"encoding/base64"
	"net"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"time"

	"guts/proto"
)

const (
	muxReadChunk      = 32 << 10
	muxStreamBuffer   = 1 << 20 // downstream bytes held per stream before backpressure
	muxStreamsPerSess = 256
)

// stream is one destination connection inside a multiplexed session.
type stream struct {
	id   uint32
	conn net.Conn

	mu      sync.Mutex
	buf     bytes.Buffer
	eof     bool // the destination ended
	eofSent bool // that end was already handed to the phone
	closed  bool
	drained chan struct{}
}

func newStream(id uint32, conn net.Conn) *stream {
	return &stream{id: id, conn: conn, drained: make(chan struct{}, 1)}
}

// readLoop fills this stream's buffer, stopping while it is full so a slow phone cannot make the
// exit node buffer without bound.
func (st *stream) readLoop(signal func()) {
	chunk := make([]byte, muxReadChunk)
	for {
		st.mu.Lock()
		for st.buf.Len() >= muxStreamBuffer && !st.closed {
			st.mu.Unlock()
			select {
			case <-st.drained:
			case <-time.After(time.Second):
			}
			st.mu.Lock()
		}
		closed := st.closed
		st.mu.Unlock()
		if closed {
			return
		}

		n, err := st.conn.Read(chunk)
		if n > 0 {
			st.mu.Lock()
			st.buf.Write(chunk[:n])
			st.mu.Unlock()
			signal()
		}
		if err != nil {
			st.mu.Lock()
			st.eof = true
			st.mu.Unlock()
			signal()
			return
		}
	}
}

func (st *stream) write(data []byte) error {
	if _, err := st.conn.Write(data); err != nil {
		return err
	}
	return nil
}

func (st *stream) close() {
	st.mu.Lock()
	if st.closed {
		st.mu.Unlock()
		return
	}
	st.closed = true
	st.mu.Unlock()
	_ = st.conn.Close()
}

// take removes up to max bytes, and says whether this stream ended: an end is answered once, after
// its last bytes.
func (st *stream) take(max int) ([]byte, bool) {
	st.mu.Lock()
	if max <= 0 || max > st.buf.Len() {
		max = st.buf.Len()
	}
	out := make([]byte, max)
	if max > 0 {
		_, _ = st.buf.Read(out)
	}
	eof := st.eof && st.buf.Len() == 0 && !st.eofSent
	if eof {
		st.eofSent = true
	}
	st.mu.Unlock()
	if max > 0 {
		select {
		case st.drained <- struct{}{}:
		default:
		}
	}
	return out, eof
}

// muxSession is a set of streams the phone multiplexes over one session id.
type muxSession struct {
	mu       sync.Mutex
	streams  map[uint32]*stream
	closed   bool
	lastUsed time.Time

	notify chan struct{} // signalled when any stream has bytes or ended
}

func newMuxSession() *muxSession {
	return &muxSession{streams: map[uint32]*stream{}, lastUsed: time.Now(), notify: make(chan struct{}, 1)}
}

func (m *muxSession) signal() {
	select {
	case m.notify <- struct{}{}:
	default:
	}
}

func (m *muxSession) touch() {
	m.mu.Lock()
	m.lastUsed = time.Now()
	m.mu.Unlock()
}

func (m *muxSession) idle(since time.Duration) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return time.Since(m.lastUsed) > since
}

// add registers a stream, refusing one whose id is taken or whose session is full.
func (m *muxSession) add(st *stream) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return net.ErrClosed
	}
	if _, taken := m.streams[st.id]; taken {
		return errStreamTaken
	}
	if len(m.streams) >= muxStreamsPerSess {
		return errTooManyStreams
	}
	m.streams[st.id] = st
	return nil
}

func (m *muxSession) stream(id uint32) *stream {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.streams[id]
}

func (m *muxSession) dropStream(id uint32) {
	m.mu.Lock()
	st := m.streams[id]
	delete(m.streams, id)
	m.mu.Unlock()
	if st != nil {
		st.close()
	}
	m.signal()
}

func (m *muxSession) live() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.streams)
}

func (m *muxSession) close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	streams := make([]*stream, 0, len(m.streams))
	for _, st := range m.streams {
		streams = append(streams, st)
	}
	m.streams = map[uint32]*stream{}
	m.mu.Unlock()
	for _, st := range streams {
		st.close()
	}
	m.signal()
}

// takeFrames collects what every stream holds into one poll answer of at most max bytes.
func (m *muxSession) takeFrames(max int) []byte {
	m.mu.Lock()
	ids := make([]uint32, 0, len(m.streams))
	for id := range m.streams {
		ids = append(ids, id)
	}
	streams := make(map[uint32]*stream, len(m.streams))
	for id, st := range m.streams {
		streams[id] = st
	}
	m.mu.Unlock()
	if len(ids) == 0 {
		return nil
	}
	// A stable order keeps a quiet stream from being starved by a busy one on every poll.
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })

	budget := max
	if budget <= 0 {
		budget = proto.DefaultPollMax
	}
	frames := make([]proto.Frame, 0, len(ids))
	for _, id := range ids {
		st := streams[id]
		if st == nil {
			continue
		}
		// Room for a payload frame and, if the stream ends now, its end frame as well: both must
		// fit, or the end would be lost behind a budget the phone never gets back.
		room := budget - 2*proto.FrameHeader
		if room <= 0 {
			break
		}
		data, eof := st.take(room)
		if len(data) == 0 && !eof {
			continue
		}
		frames = append(frames, proto.Frame{S: id, D: data, EOF: eof})
		if len(data) > 0 {
			budget -= proto.FrameHeader + len(data)
		}
		if eof {
			budget -= proto.FrameHeader
		}
	}
	if len(frames) == 0 {
		return nil
	}
	return proto.EncodeFrames(frames)
}

var (
	errStreamTaken    = netError("guts: that stream id is taken")
	errTooManyStreams = netError("guts: too many streams on one session")
)

type netError string

func (e netError) Error() string { return string(e) }

// ---------------------------------------------------------------------------------------------
// The node's side of a multiplexed session

// muxGet finds a multiplexed session and marks it used.
func (n *Node) muxGet(sid string) *muxSession {
	n.mu.Lock()
	defer n.mu.Unlock()
	m := n.muxes[sid]
	if m != nil {
		m.touch()
	}
	return m
}

// dropMux ends a multiplexed session and every stream in it.
func (n *Node) dropMux(sid string) {
	n.mu.Lock()
	m := n.muxes[sid]
	delete(n.muxes, sid)
	n.mu.Unlock()
	if m != nil {
		m.close()
	}
}

// handleOpenMux opens one stream of a multiplexed session, creating the session with the first one.
func (n *Node) handleOpenMux(w http.ResponseWriter, req proto.Request) {
	if req.Host == "" || req.Port <= 0 || req.Port > 65535 {
		writeJSON(w, proto.Response{Error: "bad destination"})
		return
	}
	if req.S == 0 {
		writeJSON(w, proto.Response{Error: "a stream id is required"})
		return
	}

	m := n.muxGet(req.Sid)
	sid := req.Sid
	if m == nil {
		// A stream that names a session the node does not hold is refused rather than silently
		// opened on a new one: the phone's streams must stay together to save the calls.
		if req.Sid != "" {
			writeJSON(w, proto.Response{Error: "no session"})
			return
		}
		if n.LiveSessions() >= maxSessions {
			writeJSON(w, proto.Response{Error: "too many sessions"})
			return
		}
		m = newMuxSession()
		sid = newID()
		n.mu.Lock()
		n.muxes[sid] = m
		n.mu.Unlock()
	}

	conn, err := net.DialTimeout("tcp", net.JoinHostPort(req.Host, strconv.Itoa(req.Port)), dialTimeout)
	if err != nil {
		writeJSON(w, proto.Response{Error: "dial: " + err.Error()})
		return
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	st := newStream(req.S, conn)
	if err := m.add(st); err != nil {
		_ = conn.Close()
		writeJSON(w, proto.Response{Error: err.Error()})
		return
	}
	go st.readLoop(m.signal)
	writeJSON(w, proto.Response{OK: true, Sid: sid, Info: proto.MuxCapability})
}

func (n *Node) serveMuxPush(w http.ResponseWriter, req proto.Request) {
	m := n.muxGet(req.Sid)
	if m == nil {
		writeJSON(w, proto.Response{Error: "no session"})
		return
	}
	st := m.stream(req.S)
	if st == nil {
		writeJSON(w, proto.Response{Error: "no stream"})
		return
	}
	data, err := base64.StdEncoding.DecodeString(req.D)
	if err != nil {
		writeJSON(w, proto.Response{Error: "base64: " + err.Error()})
		return
	}
	if err := st.write(data); err != nil {
		writeJSON(w, proto.Response{Error: "write: " + err.Error()})
		return
	}
	writeJSON(w, proto.Response{OK: true, Sid: req.Sid})
}

func (n *Node) serveMuxPoll(w http.ResponseWriter, req proto.Request) {
	m := n.muxGet(req.Sid)
	if m == nil {
		writeJSON(w, proto.Response{Error: "no session"})
		return
	}
	wait := time.Duration(req.T) * time.Millisecond
	if wait <= 0 {
		wait = proto.DefaultPollMs * time.Millisecond
	}
	if wait > proto.MaxPollMs*time.Millisecond {
		wait = proto.MaxPollMs * time.Millisecond
	}
	deadline := time.After(wait)
	for {
		if body := m.takeFrames(req.N); len(body) > 0 {
			writeJSON(w, proto.Response{OK: true, Sid: req.Sid, D: base64.StdEncoding.EncodeToString(body)})
			return
		}
		// A session with no live stream is not a dead session: the phone keeps it for the next
		// connection instead of paying a new open. A session the node forgot answers "no session"
		// on the next call, which is how the phone learns to start another.
		select {
		case <-m.notify:
		case <-deadline:
			writeJSON(w, proto.Response{OK: true, Sid: req.Sid})
			return
		}
	}
}

func (n *Node) serveMuxClose(w http.ResponseWriter, req proto.Request) {
	if req.S == 0 {
		n.dropMux(req.Sid)
		writeJSON(w, proto.Response{OK: true})
		return
	}
	if m := n.muxGet(req.Sid); m != nil {
		m.dropStream(req.S)
	}
	writeJSON(w, proto.Response{OK: true, Sid: req.Sid})
}
