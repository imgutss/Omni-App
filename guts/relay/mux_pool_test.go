package relay_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"guts/exitnode"
	"guts/proto"
	"guts/relay"
)

// slowOpenDoor is a door whose opens take a while, so serialized opens are visible in the clock.
type slowOpenDoor struct {
	inflight atomic.Int32
	peak     atomic.Int32

	mu     sync.Mutex
	pushed []byte
}

func (d *slowOpenDoor) Describe() string { return "slow-open fake" }

func (d *slowOpenDoor) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	switch req.Op {
	case proto.OpOpen:
		now := d.inflight.Add(1)
		for {
			peak := d.peak.Load()
			if now <= peak || d.peak.CompareAndSwap(peak, now) {
				break
			}
		}
		time.Sleep(200 * time.Millisecond)
		d.inflight.Add(-1)
		return proto.Response{OK: true, Sid: "fake", Info: proto.MuxCapabilityV2}, nil
	case proto.OpPoll:
		time.Sleep(20 * time.Millisecond)
		return proto.Response{OK: true, Sid: req.Sid}, nil
	case proto.OpPush:
		data, err := base64.StdEncoding.DecodeString(req.D)
		if err != nil {
			return proto.Response{Error: err.Error()}, nil
		}
		d.mu.Lock()
		d.pushed = append(d.pushed, data...)
		d.mu.Unlock()
		return proto.Response{OK: true, Sid: req.Sid}, nil
	default:
		return proto.Response{OK: true, Sid: req.Sid}, nil
	}
}

// Once the session exists, a page opening several connections pays one open of latency, not one
// per connection in a row.
func TestOpensRunConcurrentlyOnceTheSessionExists(t *testing.T) {
	door := &slowOpenDoor{}
	m := relay.NewMux(door, t.Logf)
	defer m.Close()

	first, err := m.Dial(context.Background(), "example.com", 443)
	if err != nil {
		t.Fatalf("first dial: %v", err)
	}
	defer first.Close()

	const extra = 4
	start := time.Now()
	var wg sync.WaitGroup
	errs := make(chan error, extra)
	for i := 0; i < extra; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			st, err := m.Dial(context.Background(), "example.com", 443)
			if err == nil {
				_ = st.Close()
			}
			errs <- err
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatalf("dial: %v", err)
		}
	}
	if took := time.Since(start); took > 550*time.Millisecond {
		t.Fatalf("%d opens took %v; they were serialized somewhere", extra, took)
	}
	if peak := door.peak.Load(); peak < 3 {
		t.Fatalf("only %d opens were ever in flight together", peak)
	}
}

// A write larger than the pending bound is queued in pieces, not left waiting for room that
// can never exist inside one indivisible chunk.
func TestAWriteLargerThanThePendingBoundStillFlows(t *testing.T) {
	door := &slowOpenDoor{}
	m := relay.NewMux(door, t.Logf)
	defer m.Close()

	st, err := m.Dial(context.Background(), "example.com", 443)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	payload := make([]byte, (3<<19)+12345) // 1.5 MiB and a tail
	for i := range payload {
		payload[i] = byte(i)
	}
	done := make(chan error, 1)
	go func() {
		n, err := st.Write(payload)
		if err == nil && n != len(payload) {
			err = io.ErrShortWrite
		}
		if err == nil {
			err = st.Close()
		}
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("write/close: %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("the oversized write deadlocked")
	}

	door.mu.Lock()
	pushed := append([]byte(nil), door.pushed...)
	door.mu.Unlock()
	if !bytes.Equal(pushed, payload) {
		t.Fatalf("pushed %d bytes, want the exact %d-byte payload", len(pushed), len(payload))
	}
}

// The first bytes handed to DialData reach the destination with the open itself.
func TestDialDataDeliversTheFirstBytes(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "first bytes arrived")
	}))
	defer web.Close()
	node := exitnode.New("testkey")
	defer node.Close()
	exitSrv := httptest.NewServer(node)
	defer exitSrv.Close()

	m := relay.NewMux(relay.NewDirect(exitSrv.URL, "testkey"), t.Logf)
	defer m.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	st, err := m.DialData(context.Background(), host, port, []byte("GET / HTTP/1.0\r\n\r\n"))
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer st.Close()
	done := make(chan string, 1)
	go func() {
		body, _ := io.ReadAll(st)
		done <- string(body)
	}()
	select {
	case body := <-done:
		if body == "" || !contains(body, "first bytes arrived") {
			t.Fatalf("the destination never saw the first bytes; read %q", body)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("no answer: the first bytes were lost with the open")
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// A pool spreads connections over several sessions, which is what lifts the downstream ceiling:
// each session keeps its own poll in flight.
func TestThePoolSpreadsConnectionsOverSessions(t *testing.T) {
	const connections = 6
	arrived := make(chan struct{}, connections)
	gate := make(chan struct{})
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		arrived <- struct{}{}
		<-gate
		_, _ = io.WriteString(w, "pooled")
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	node := exitnode.New("testkey")
	defer node.Close()
	exitSrv := httptest.NewServer(node)
	defer exitSrv.Close()
	proxy := relay.NewProxy(relay.NewDirect(exitSrv.URL, "testkey"), "127.0.0.1:0", t.Logf)
	proxy.UseMux(true)
	proxy.SetMuxSessions(3)
	addr, err := proxy.Listen()
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = proxy.Serve() }()
	defer proxy.Close()

	var wg sync.WaitGroup
	for i := 0; i < connections; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn := socksDial(t, addr, host, port)
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
			_, _ = io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n")
			_, _ = io.ReadAll(conn)
		}()
	}
	for i := 0; i < connections; i++ {
		<-arrived
	}
	if live := node.LiveSessions(); live < 2 {
		t.Fatalf("the exit node held %d sessions for %d pooled connections, want at least 2", live, connections)
	}
	close(gate)
	wg.Wait()
}
