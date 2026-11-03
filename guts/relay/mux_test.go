package relay_test

import (
	"bytes"
	"crypto/rand"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"guts/exitnode"
	"guts/relay"
)

// startMuxStack is the same path as startStack, with one multiplexed session behind it. It returns
// the proxy address and a function that reports how many sessions the exit node holds.
func startMuxStack(t *testing.T) (string, func() int) {
	t.Helper()
	node := exitnode.New("testkey")
	t.Cleanup(node.Close)
	exitSrv := httptest.NewServer(node)
	t.Cleanup(exitSrv.Close)

	door := relay.NewDirect(exitSrv.URL, "testkey")
	proxy := relay.NewProxy(door, "127.0.0.1:0", t.Logf)
	proxy.UseMux(true)
	addr, err := proxy.Listen()
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = proxy.Serve() }()
	t.Cleanup(func() { _ = proxy.Close() })
	return addr, node.LiveSessions
}

func TestManyConnectionsShareOneSession(t *testing.T) {
	payload := make([]byte, 256<<10)
	_, _ = rand.Read(payload)
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		_, _ = w.Write(payload)
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	addr, sessions := startMuxStack(t)
	const connections = 6
	var wg sync.WaitGroup
	errs := make(chan string, connections)
	for i := 0; i < connections; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn := socksDial(t, addr, host, port)
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(120 * time.Second))
			if _, err := io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n"); err != nil {
				errs <- "write: " + err.Error()
				return
			}
			body, err := io.ReadAll(conn)
			if err != nil {
				errs <- "read: " + err.Error()
				return
			}
			idx := bytes.Index(body, []byte("\r\n\r\n"))
			if idx < 0 {
				errs <- "no header end"
				return
			}
			if got := body[idx+4:]; !bytes.Equal(got, payload) {
				errs <- "payload differs: got " + strconv.Itoa(len(got))
			}
		}()
	}
	wg.Wait()
	close(errs)
	for message := range errs {
		t.Fatalf("%s", message)
	}

	// The point of the whole thing: six connections, one session, so one door call per interval.
	if live := sessions(); live != 1 {
		t.Fatalf("the exit node held %d sessions, want 1", live)
	}
}

func TestAStreamCanBeClosedWhileAnotherContinues(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(1500 * time.Millisecond)
		_, _ = io.WriteString(w, "slow answer")
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	addr, _ := startMuxStack(t)

	quiet := socksDial(t, addr, host, port)
	_ = quiet.SetDeadline(time.Now().Add(60 * time.Second))
	_ = quiet.Close()

	conn := socksDial(t, addr, host, port)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	if _, err := io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatalf("write: %v", err)
	}
	body, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Contains(body, []byte("slow answer")) {
		t.Fatalf("the second stream did not receive its answer: %q", body)
	}
}

func TestStreamsDoNotSeeEachOthersBytes(t *testing.T) {
	first := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "AAAA")
	}))
	defer first.Close()
	second := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "BBBB")
	}))
	defer second.Close()

	addr, _ := startMuxStack(t)
	read := func(srv *httptest.Server) string {
		host, portStr, _ := net.SplitHostPort(srv.Listener.Addr().String())
		port, _ := strconv.Atoi(portStr)
		conn := socksDial(t, addr, host, port)
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
		_, _ = io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n")
		body, _ := io.ReadAll(conn)
		idx := bytes.Index(body, []byte("\r\n\r\n"))
		if idx < 0 {
			return ""
		}
		return string(body[idx+4:])
	}

	var a, b string
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a = read(first) }()
	go func() { defer wg.Done(); b = read(second) }()
	wg.Wait()

	if a != "AAAA" || b != "BBBB" {
		t.Fatalf("streams crossed: first=%q second=%q", a, b)
	}
}
