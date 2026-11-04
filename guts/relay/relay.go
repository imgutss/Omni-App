// Package relay is the guts client: a SOCKS5 listener whose sessions are carried in JSON calls to
// a door (a Google Apps Script web app) that forwards them to an exit node.
//
// The door is never dialled by address: the connection goes to a Google edge address with the SNI
// of a Google host, and the request carries the door's own host name, so the network in between
// only ever sees a TLS session to Google. See NewFronted.
package relay

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"guts/protect"
	"guts/proto"
)

// Door carries one guts call and returns its answer. Every transport implements it, so the rest of
// the client does not know whether it talks to an exit node directly or through Google.
type Door interface {
	Call(ctx context.Context, req proto.Request) (proto.Response, error)
	Describe() string
}

// ---------------------------------------------------------------------------------------------
// Transports

// Direct posts straight to an exit node. It is what a home server, a CI runner or a local test
// uses; nothing is concealed, so a censored network cannot reach it.
type Direct struct {
	url  string
	key  string
	http *http.Client
}

// NewDirect posts to an exit node's endpoint.
func NewDirect(endpoint, key string) *Direct {
	return &Direct{
		url: endpoint,
		key: key,
		http: &http.Client{
			Timeout: 60 * time.Second,
			Transport: &http.Transport{
				ForceAttemptHTTP2:   true,
				MaxIdleConnsPerHost: 32,
				IdleConnTimeout:     90 * time.Second,
				// On a phone this dial protects the socket from our own VPN before it connects;
				// on a desktop it is an ordinary dial. See guts/protect.
				DialContext: protect.Dial,
			},
		},
	}
}

// NewFronted posts to a door through a fixed Google edge address, with the SNI of a Google host.
// The request keeps the door's own host name, which is what the edge routes on: the network sees a
// connection to Google and cannot tell which Apps Script deployment, or that it is a relay at all.
func NewFronted(doorURL *url.URL, edgeAddr, sni, key string) (*Fronted, error) {
	if edgeAddr == "" {
		return nil, errors.New("guts: a google edge address is required")
	}
	if sni == "" {
		sni = "www.google.com"
	}
	addr := edgeAddr
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(addr, "443")
	}
	// HTTP/2 where the edge offers it: the pool's polls and pushes then share one TLS
	// connection to the edge instead of each waiting behind its own, and a slow 700 KB poll
	// answer no longer head-of-line blocks an interactive push. Where ALPN offers only
	// HTTP/1.1 the transport falls back on its own.
	tr := &http.Transport{
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        64,
		MaxIdleConnsPerHost: 32,
		IdleConnTimeout:     90 * time.Second,
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return protect.Dial(ctx, network, addr)
		},
		TLSClientConfig: &tls.Config{
			ServerName: sni,
			MinVersion: tls.VersionTLS12,
		},
	}
	return &Fronted{url: doorURL.String(), key: key, edge: addr, sni: sni, http: &http.Client{Timeout: 70 * time.Second, Transport: tr}}, nil
}

// Fronted is a door reached through a Google edge address with a Google SNI.
type Fronted struct {
	url  string
	key  string
	edge string
	sni  string
	http *http.Client
}

func (f *Fronted) Describe() string {
	return fmt.Sprintf("fronted %s (edge %s, sni %s)", hostOf(f.url), f.edge, f.sni)
}

func (f *Fronted) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	return postJSON(ctx, f.http, f.url, f.key, req)
}

func (d *Direct) Describe() string { return "direct " + hostOf(d.url) }

func (d *Direct) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	return postJSON(ctx, d.http, d.url, d.key, req)
}

func postJSON(ctx context.Context, c *http.Client, endpoint, key string, req proto.Request) (proto.Response, error) {
	req.V = proto.Version
	req.K = key
	body, err := json.Marshal(req)
	if err != nil {
		return proto.Response{}, err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return proto.Response{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Guts-Key", key)
	resp, err := c.Do(httpReq)
	if err != nil {
		return proto.Response{}, err
	}
	defer func() { _ = resp.Body.Close() }()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return proto.Response{}, err
	}
	var out proto.Response
	if err := json.Unmarshal(raw, &out); err != nil {
		// An Apps Script deployment that is misconfigured, or a Cloudflare challenge, answers with
		// HTML: say so instead of hiding it behind a decode error.
		snippet := string(raw)
		if len(snippet) > 160 {
			snippet = snippet[:160]
		}
		return proto.Response{}, fmt.Errorf("guts: answer is not guts/1 JSON (status %d): %s", resp.StatusCode, strings.TrimSpace(snippet))
	}
	if !out.OK && out.Error == "" {
		out.Error = "exit node refused the call"
	}
	return out, nil
}

func hostOf(raw string) string {
	if u, err := url.Parse(raw); err == nil {
		return u.Host
	}
	return raw
}

// ---------------------------------------------------------------------------------------------
// Sessions

// Session is one TCP connection to a destination, carried through a door.
type Session struct {
	door Door
	sid  string
}

// Open asks the exit node for a connection to host:port.
func Open(ctx context.Context, door Door, host string, port int) (*Session, error) {
	resp, err := door.Call(ctx, proto.Request{Op: proto.OpOpen, Host: host, Port: port})
	if err != nil {
		return nil, err
	}
	if !resp.OK || resp.Sid == "" {
		if resp.Error == "" {
			return nil, errors.New("guts: open refused")
		}
		return nil, errors.New("guts: " + resp.Error)
	}
	return &Session{door: door, sid: resp.Sid}, nil
}

// Push sends bytes towards the destination.
func (s *Session) Push(ctx context.Context, data []byte) error {
	for len(data) > 0 {
		n := len(data)
		if n > proto.MaxPushBytes {
			n = proto.MaxPushBytes
		}
		resp, err := s.door.Call(ctx, proto.Request{
			Op:  proto.OpPush,
			Sid: s.sid,
			D:   base64.StdEncoding.EncodeToString(data[:n]),
		})
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

// Poll waits up to timeoutMs for bytes from the destination.
func (s *Session) Poll(ctx context.Context, max, timeoutMs int) ([]byte, bool, error) {
	if max <= 0 {
		max = proto.DefaultPollMax
	}
	resp, err := s.door.Call(ctx, proto.Request{Op: proto.OpPoll, Sid: s.sid, N: max, T: timeoutMs})
	if err != nil {
		return nil, false, err
	}
	if !resp.OK {
		return nil, false, errors.New("guts: " + resp.Error)
	}
	if resp.D == "" {
		return nil, resp.EOF, nil
	}
	data, err := base64.StdEncoding.DecodeString(resp.D)
	if err != nil {
		return nil, false, err
	}
	return data, resp.EOF, nil
}

// Close ends the session at the exit node.
func (s *Session) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = s.door.Call(ctx, proto.Request{Op: proto.OpClose, Sid: s.sid})
}

// ---------------------------------------------------------------------------------------------
// SOCKS5

// Proxy serves SOCKS5 on a local address and carries every CONNECT through the door.
type Proxy struct {
	door   Door
	listen string
	log    func(string, ...any)

	mu          sync.Mutex
	listener    net.Listener
	closed      bool
	useMux      bool
	muxSessions int
	muxes       []*Mux
	fetchCA     *CertificateAuthority
}

// NewProxy builds a SOCKS5 server that carries its connections through door.
func NewProxy(door Door, listen string, logf func(string, ...any)) *Proxy {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Proxy{door: door, listen: listen, log: logf}
}

// UseMux carries every connection of this proxy as a stream of one door session, which is what
// keeps a busy phone to one poll per interval instead of one per connection.
func (p *Proxy) UseMux(enabled bool) {
	p.mu.Lock()
	p.useMux = enabled
	p.mu.Unlock()
}

// SetMuxSessions sizes the pool of multiplexed sessions connections are spread over.
//
// One session means one poll in flight, and a poll answer is at most half a megabyte, so one
// session's downstream ceiling is that divided by a door round-trip — measured at ~80 KB/s on a
// real door, however many connections shared it, which no page survives. A pool of a few
// sessions keeps that many polls in flight at once and multiplies the ceiling, while idle cost
// stays at one poll per *session* per interval instead of one per connection. Sessions are
// opened lazily, only as connections arrive to need them. The default is one, as before.
func (p *Proxy) SetMuxSessions(n int) {
	if n < 1 {
		n = 1
	}
	if n > 8 {
		n = 8
	}
	p.mu.Lock()
	p.muxSessions = n
	p.mu.Unlock()
}

// muxer returns the least loaded live multiplexer, opening another while the pool may still
// grow and every live one already carries a stream.
func (p *Proxy) muxer() *Mux {
	p.mu.Lock()
	defer p.mu.Unlock()
	target := p.muxSessions
	if target < 1 {
		target = 1
	}
	live := p.muxes[:0]
	for _, m := range p.muxes {
		if !m.Failed() {
			live = append(live, m)
		}
	}
	p.muxes = live
	var least *Mux
	for _, m := range p.muxes {
		if least == nil || m.Load() < least.Load() {
			least = m
		}
	}
	if least == nil || (len(p.muxes) < target && least.Load() >= 1) {
		least = NewMux(p.door, p.log)
		p.muxes = append(p.muxes, least)
	}
	return least
}

// Listen binds the SOCKS5 port and returns the address it holds. Call Serve after it.
func (p *Proxy) Listen() (string, error) {
	ln, err := net.Listen("tcp", p.listen)
	if err != nil {
		return "", err
	}
	p.mu.Lock()
	p.listener = ln
	p.mu.Unlock()
	return ln.Addr().String(), nil
}

// Serve accepts connections until the proxy is closed.
func (p *Proxy) Serve() error {
	p.mu.Lock()
	ln := p.listener
	p.mu.Unlock()
	if ln == nil {
		return errors.New("guts: proxy is not listening")
	}
	for {
		c, err := ln.Accept()
		if err != nil {
			p.mu.Lock()
			closed := p.closed
			p.mu.Unlock()
			if closed {
				return nil
			}
			return err
		}
		go p.handle(c)
	}
}

// Close stops the listener.
func (p *Proxy) Close() error {
	p.mu.Lock()
	p.closed = true
	ln := p.listener
	muxes := p.muxes
	p.mu.Unlock()
	for _, mux := range muxes {
		mux.Close()
	}
	if ln != nil {
		return ln.Close()
	}
	return nil
}

func (p *Proxy) handle(c net.Conn) {
	defer func() { _ = c.Close() }()
	host, port, err := socks5Handshake(c)
	if err != nil {
		p.log("socks: handshake: %v", err)
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p.mu.Lock()
	muxed := p.useMux
	fetchCA := p.fetchCA
	p.mu.Unlock()
	if fetchCA != nil {
		p.handleFetch(c, ctx, host, port, fetchCA)
		return
	}
	if muxed && p.handleMux(c, ctx, host, port) {
		return
	}
	// Either multiplexing is off, or the exit node cannot do it: this connection goes the old way,
	// one session of its own.

	sess, err := Open(ctx, p.door, host, port)
	if err != nil {
		p.log("socks: open %s:%d: %v", host, port, err)
		_, _ = c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer sess.Close()
	_, _ = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})

	var once sync.Once
	stop := func() { once.Do(cancel) }

	go p.pipeUp(ctx, c, sess, stop)
	p.pipeDown(ctx, c, sess, stop)
}

// firstDataWait is how long a warm session's new connection is held for the application's
// first bytes — for HTTPS, the ClientHello it sends the moment SOCKS answers. Bundling those
// bytes with the stream's open saves a whole door round-trip per connection.
const firstDataWait = 60 * time.Millisecond

// handleMux carries one SOCKS connection as a stream of a shared session, and reports whether it
// did: the caller falls back to a session of its own when the exit node cannot multiplex.
func (p *Proxy) handleMux(c net.Conn, ctx context.Context, host string, port int) bool {
	mux := p.muxer()

	var st *Stream
	var err error
	if mux.Ready() {
		// The session is warm, so answer SOCKS first and take the application's first bytes
		// with the open instead of paying open, then push, as two round-trips in a row. This
		// needs the session already proven: an exit node that cannot multiplex must still be
		// discoverable with no application bytes consumed, so the fallback stays clean.
		_, _ = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		var initial []byte
		buf := make([]byte, 64<<10)
		_ = c.SetReadDeadline(time.Now().Add(firstDataWait))
		if n, readErr := c.Read(buf); n > 0 {
			initial = buf[:n]
		} else if errors.Is(readErr, io.EOF) {
			return true
		}
		_ = c.SetDeadline(time.Time{})
		st, err = mux.DialData(ctx, host, port, initial)
		if err != nil {
			p.log("socks: open %s:%d: %v", host, port, err)
			return true
		}
	} else {
		st, err = mux.Dial(ctx, host, port)
		if errors.Is(err, ErrNoMux) {
			p.UseMux(false)
			p.log("socks: the exit node does not multiplex; this phone pays one call per connection until it is redeployed")
			return false
		}
		if err != nil {
			p.log("socks: open %s:%d: %v", host, port, err)
			_, _ = c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
			return true
		}
		_, _ = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		// The read deadline the handshake set must not end a long-lived upload.
		_ = c.SetDeadline(time.Time{})
	}
	defer st.Close()

	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.CopyBuffer(st, c, make([]byte, 128<<10))
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.CopyBuffer(c, st, make([]byte, 128<<10))
		done <- struct{}{}
	}()
	<-done
	// One side ended: end the other, so a closed stream does not keep the connection open.
	_ = c.Close()
	_ = st.Close()
	<-done
	return true
}

// pipeUp carries what the app writes into the session, one push per read.
func (p *Proxy) pipeUp(ctx context.Context, c net.Conn, sess *Session, stop func()) {
	defer stop()
	buf := make([]byte, 128<<10)
	for {
		n, err := c.Read(buf)
		if n > 0 {
			if pushErr := sess.Push(ctx, buf[:n]); pushErr != nil {
				p.log("socks: push: %v", pushErr)
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// pipeDown carries the destination's bytes back, waiting longer on a quiet connection so an idle
// session costs as few door calls as possible.
func (p *Proxy) pipeDown(ctx context.Context, c net.Conn, sess *Session, stop func()) {
	defer stop()
	quiet := 0
	for {
		if ctx.Err() != nil {
			return
		}
		wait := proto.DefaultPollMs
		if quiet >= 2 {
			wait = proto.IdlePollMs
		}
		data, eof, err := sess.Poll(ctx, proto.MaxPollMax, wait)
		if err != nil {
			if ctx.Err() == nil {
				p.log("socks: poll: %v", err)
			}
			return
		}
		if len(data) > 0 {
			quiet = 0
			if _, err := c.Write(data); err != nil {
				return
			}
		} else {
			quiet++
		}
		if eof {
			return
		}
	}
}

// socks5Handshake answers a SOCKS5 CONNECT and returns its destination.
func socks5Handshake(c net.Conn) (string, int, error) {
	_ = c.SetDeadline(time.Now().Add(20 * time.Second))
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil {
		return "", 0, err
	}
	if head[0] != 5 {
		return "", 0, errors.New("not socks5")
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", 0, err
	}
	if _, err := c.Write([]byte{5, 0}); err != nil { // no authentication
		return "", 0, err
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil {
		return "", 0, err
	}
	if req[1] != 1 { // CONNECT only
		return "", 0, fmt.Errorf("unsupported command %d", req[1])
	}
	var host string
	switch req[3] {
	case 1:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(c, ip); err != nil {
			return "", 0, err
		}
		host = net.IP(ip).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return "", 0, err
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, name); err != nil {
			return "", 0, err
		}
		host = string(name)
	case 4:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(c, ip); err != nil {
			return "", 0, err
		}
		host = net.IP(ip).String()
	default:
		return "", 0, errors.New("bad address type")
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(c, portBuf); err != nil {
		return "", 0, err
	}
	_ = c.SetDeadline(time.Time{})
	return host, int(binary.BigEndian.Uint16(portBuf)), nil
}

// Check runs one ping through the door, for a "test this door" button and for the CI smoke test.
func Check(ctx context.Context, door Door) (string, error) {
	resp, err := door.Call(ctx, proto.Request{Op: proto.OpPing})
	if err != nil {
		return "", err
	}
	if !resp.OK {
		return "", errors.New("guts: " + resp.Error)
	}
	return resp.Info, nil
}

// ParseDestination reads "host:port" the way a config file writes it.
func ParseDestination(s string) (string, int, error) {
	host, portStr, err := net.SplitHostPort(s)
	if err != nil {
		return "", 0, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return "", 0, err
	}
	return host, port, nil
}
