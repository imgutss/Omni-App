// Package exitnode holds the real TCP sockets of guts: it is reached only through the door (a Google
// Apps Script web app), never by the phone directly.
//
// Every call is one JSON body on POST /g, see package guts/proto. It keeps no state outside the
// sessions it holds, so it runs unchanged on a VPS, a free CI runner or a home server.
package exitnode

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"guts/proto"
)

const (
	maxBodyBytes    = 8 << 20  // one push or poll body
	readChunk       = 32 << 10 // per read from the destination
	maxBuffered     = 4 << 20  // downstream bytes held for a session before backpressure
	maxSessions     = 512
	dialTimeout     = 15 * time.Second
	janitorInterval = 15 * time.Second
)

type session struct {
	conn net.Conn

	mu       sync.Mutex
	buf      bytes.Buffer
	eof      bool
	closed   bool
	lastUsed time.Time

	notify  chan struct{} // signalled when buf grows
	drained chan struct{} // signalled when buf shrinks
}

func newSession(conn net.Conn) *session {
	return &session{
		conn:     conn,
		lastUsed: time.Now(),
		notify:   make(chan struct{}, 1),
		drained:  make(chan struct{}, 1),
	}
}

func (s *session) touch() {
	s.mu.Lock()
	s.lastUsed = time.Now()
	s.mu.Unlock()
}

func (s *session) idle(since time.Duration) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.lastUsed) > since
}

func (s *session) signal() {
	select {
	case s.notify <- struct{}{}:
	default:
	}
}

// readLoop moves bytes from the destination into the session buffer, with backpressure: it stops
// reading while the buffer is full, so a phone that polls slowly cannot make the exit node buffer
// without bound.
func (s *session) readLoop() {
	chunk := make([]byte, readChunk)
	for {
		s.mu.Lock()
		for s.buf.Len() >= maxBuffered && !s.closed {
			s.mu.Unlock()
			select {
			case <-s.drained:
			case <-time.After(time.Second):
			}
			s.mu.Lock()
		}
		closed := s.closed
		s.mu.Unlock()
		if closed {
			return
		}

		n, err := s.conn.Read(chunk)
		if n > 0 {
			s.mu.Lock()
			s.buf.Write(chunk[:n])
			s.mu.Unlock()
			s.signal()
		}
		if err != nil {
			s.mu.Lock()
			s.eof = true
			s.mu.Unlock()
			s.signal()
			return
		}
	}
}

func (s *session) close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.mu.Unlock()
	_ = s.conn.Close()
	s.signal()
}

// take removes up to max bytes from the buffer.
func (s *session) take(max int) ([]byte, bool) {
	s.mu.Lock()
	if max <= 0 || max > s.buf.Len() {
		max = s.buf.Len()
	}
	out := make([]byte, max)
	if max > 0 {
		_, _ = io.ReadFull(&s.buf, out)
	}
	eof := s.eof && s.buf.Len() == 0
	s.mu.Unlock()
	if max > 0 {
		select {
		case s.drained <- struct{}{}:
		default:
		}
	}
	return out, eof
}

// Node is an exit node: the sessions it holds and the key it accepts.
type Node struct {
	key string

	mu       sync.Mutex
	sessions map[string]*session
	muxes    map[string]*muxSession
}

// New builds an exit node that answers calls signed with key.
func New(key string) *Node {
	if key == "" {
		panic("guts: an exit node needs a shared key")
	}
	n := &Node{key: key, sessions: map[string]*session{}, muxes: map[string]*muxSession{}}
	go n.janitor()
	return n
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(b)
}

func (n *Node) get(sid string) *session {
	n.mu.Lock()
	defer n.mu.Unlock()
	s := n.sessions[sid]
	if s != nil {
		s.touch()
	}
	return s
}

func (n *Node) drop(sid string) {
	n.mu.Lock()
	s := n.sessions[sid]
	delete(n.sessions, sid)
	n.mu.Unlock()
	if s != nil {
		s.close()
	}
}

// LiveSessions reports how many sessions the node holds, for /healthz.
func (n *Node) LiveSessions() int {
	n.mu.Lock()
	defer n.mu.Unlock()
	return len(n.sessions) + len(n.muxes)
}

// Close ends every session.
func (n *Node) Close() {
	n.mu.Lock()
	sids := make([]string, 0, len(n.sessions)+len(n.muxes))
	for sid := range n.sessions {
		sids = append(sids, sid)
	}
	for sid := range n.muxes {
		sids = append(sids, sid)
	}
	n.mu.Unlock()
	for _, sid := range sids {
		n.dropMux(sid)
		n.drop(sid)
	}
}

func (n *Node) janitor() {
	ticker := time.NewTicker(janitorInterval)
	defer ticker.Stop()
	for range ticker.C {
		var drop, dropMux []string
		n.mu.Lock()
		for sid, s := range n.sessions {
			if s.idle(proto.SessionIdleSecs * time.Second) {
				drop = append(drop, sid)
			}
		}
		for sid, m := range n.muxes {
			// A multiplexed session carries many connections, so it may sit quiet longer.
			if m.idle(2 * proto.SessionIdleSecs * time.Second) {
				dropMux = append(dropMux, sid)
			}
		}
		n.mu.Unlock()
		for _, sid := range drop {
			log.Printf("exit: closing idle session %s", sid)
			n.drop(sid)
		}
		for _, sid := range dropMux {
			log.Printf("exit: closing idle mux session %s", sid)
			n.dropMux(sid)
		}
	}
}

// ServeHTTP answers one guts call.
func (n *Node) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "post only", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil {
		writeJSON(w, proto.Response{Error: "read: " + err.Error()})
		return
	}
	var req proto.Request
	if err := json.Unmarshal(body, &req); err != nil {
		writeJSON(w, proto.Response{Error: "json: " + err.Error()})
		return
	}
	key := req.K
	if key == "" {
		key = r.Header.Get("X-Guts-Key")
	}
	if subtle.ConstantTimeCompare([]byte(key), []byte(n.key)) != 1 {
		writeJSON(w, proto.Response{Error: "unauthorized"})
		return
	}

	switch req.Op {
	case proto.OpPing:
		writeJSON(w, proto.Response{OK: true, Info: "guts-exit/" + proto.VersionTag})

	case proto.OpOpen:
		n.handleOpen(w, req)

	case proto.OpPush:
		if req.MU {
			n.serveMuxPush(w, req)
			return
		}
		s := n.get(req.Sid)
		if s == nil {
			writeJSON(w, proto.Response{Error: "no session"})
			return
		}
		data, err := base64.StdEncoding.DecodeString(req.D)
		if err != nil {
			writeJSON(w, proto.Response{Error: "base64: " + err.Error()})
			return
		}
		if _, err := s.conn.Write(data); err != nil {
			writeJSON(w, proto.Response{Error: "write: " + err.Error()})
			return
		}
		writeJSON(w, proto.Response{OK: true})

	case proto.OpPoll:
		if req.MU {
			n.serveMuxPoll(w, req)
			return
		}
		s := n.get(req.Sid)
		if s == nil {
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
			data, eof := s.take(req.N)
			if len(data) > 0 || eof {
				writeJSON(w, proto.Response{OK: true, D: base64.StdEncoding.EncodeToString(data), EOF: eof})
				return
			}
			select {
			case <-s.notify:
			case <-deadline:
				writeJSON(w, proto.Response{OK: true})
				return
			}
		}

	case proto.OpClose:
		if req.MU {
			n.serveMuxClose(w, req)
			return
		}
		n.drop(req.Sid)
		writeJSON(w, proto.Response{OK: true})

	default:
		writeJSON(w, proto.Response{Error: "unknown op " + req.Op})
	}
}

func (n *Node) handleOpen(w http.ResponseWriter, req proto.Request) {
	if req.MU {
		n.handleOpenMux(w, req)
		return
	}
	if req.Host == "" || req.Port <= 0 || req.Port > 65535 {
		writeJSON(w, proto.Response{Error: "bad destination"})
		return
	}
	if n.LiveSessions() >= maxSessions {
		writeJSON(w, proto.Response{Error: "too many sessions"})
		return
	}

	conn, err := net.DialTimeout("tcp", net.JoinHostPort(req.Host, strconv.Itoa(req.Port)), dialTimeout)
	if err != nil {
		writeJSON(w, proto.Response{Error: "dial: " + err.Error()})
		return
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	s := newSession(conn)
	sid := newID()
	n.mu.Lock()
	n.sessions[sid] = s
	n.mu.Unlock()
	go s.readLoop()
	writeJSON(w, proto.Response{OK: true, Sid: sid})
}

func writeJSON(w http.ResponseWriter, resp proto.Response) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}
