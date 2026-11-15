package relay_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
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

// socksDial opens a SOCKS5 CONNECT through addr, the way an app behind the proxy does.
func socksDial(t *testing.T, addr, host string, port int) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", addr, 10*time.Second)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	if _, err = c.Write([]byte{5, 1, 0}); err != nil {
		t.Fatalf("greeting: %v", err)
	}
	reply := make([]byte, 2)
	if _, err = io.ReadFull(c, reply); err != nil {
		t.Fatalf("greeting reply: %v", err)
	}
	req := []byte{5, 1, 0, 3, byte(len(host))}
	req = append(req, host...)
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	if _, err = c.Write(req); err != nil {
		t.Fatalf("request: %v", err)
	}
	head := make([]byte, 4)
	if _, err = io.ReadFull(c, head); err != nil {
		t.Fatalf("request reply: %v", err)
	}
	if head[1] != 0 {
		t.Fatalf("socks refused the connect: %v", head)
	}
	switch head[3] {
	case 1:
		_, _ = io.ReadFull(c, make([]byte, 4+2))
	case 4:
		_, _ = io.ReadFull(c, make([]byte, 16+2))
	case 3:
		l := make([]byte, 1)
		_, _ = io.ReadFull(c, l)
		_, _ = io.ReadFull(c, make([]byte, int(l[0])+2))
	}
	return c
}

func startStack(t *testing.T, target string, targetPort int) string {
	t.Helper()
	node := exitnode.New("testkey")
	t.Cleanup(node.Close)
	exitSrv := httptest.NewServer(node)
	t.Cleanup(exitSrv.Close)

	door := relay.NewDirect(exitSrv.URL, "testkey")
	proxy := relay.NewProxy(door, "127.0.0.1:0", t.Logf)
	addr, err := proxy.Listen()
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = proxy.Serve() }()
	t.Cleanup(func() { _ = proxy.Close() })
	return addr
}

func TestBigDownloadArrivesIntact(t *testing.T) {
	payload := make([]byte, 2<<20)
	_, _ = rand.Read(payload)
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		_, _ = w.Write(payload)
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	addr := startStack(t, host, port)
	conn := socksDial(t, addr, host, port)
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(90 * time.Second))
	if _, err := io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatalf("write request: %v", err)
	}
	body, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	idx := bytes.Index(body, []byte("\r\n\r\n"))
	if idx < 0 {
		t.Fatalf("no header end in %d bytes", len(body))
	}
	got := body[idx+4:]
	if !bytes.Equal(got, payload) {
		t.Fatalf("body differs: got %d bytes, want %d", len(got), len(payload))
	}
}

func TestBidirectionalTraffic(t *testing.T) {
	srv, other := net.Pipe()
	defer func() { _ = srv.Close(); _ = other.Close() }()
	tcp := tcpListenerFor(t)
	defer func() { _ = tcp.Close() }()

	go func() {
		c, err := tcp.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		go func() { _, _ = io.Copy(c, srv) }()
		_, _ = io.Copy(c, c)
	}()

	host, portStr, _ := net.SplitHostPort(tcp.Addr().String())
	port, _ := strconv.Atoi(portStr)
	addr := startStack(t, host, port)

	conn := socksDial(t, addr, host, port)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	msg := []byte("guts echo round trip")
	if _, err := conn.Write(msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	back := make([]byte, len(msg))
	if _, err := io.ReadFull(conn, back); err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(back, msg) {
		t.Fatalf("echo differs: %q", back)
	}
}

func TestManyConnectionsAtOnce(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "ok")
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)
	addr := startStack(t, host, port)

	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn := socksDial(t, addr, host, port)
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
			_, _ = io.WriteString(conn, "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")
			body, err := io.ReadAll(conn)
			if err != nil {
				errs <- err
				return
			}
			if !bytes.Contains(body, []byte("ok")) {
				errs <- io.ErrUnexpectedEOF
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("concurrent connection failed: %v", err)
	}
}

func TestPingThroughDoor(t *testing.T) {
	node := exitnode.New("testkey")
	defer node.Close()
	srv := httptest.NewServer(node)
	defer srv.Close()

	info, err := relay.Check(context.Background(), relay.NewDirect(srv.URL, "testkey"))
	if err != nil {
		t.Fatalf("check: %v", err)
	}
	if info == "" {
		t.Fatal("check returned no build info")
	}
}

func TestWrongKeyIsRefused(t *testing.T) {
	node := exitnode.New("right")
	defer node.Close()
	srv := httptest.NewServer(node)
	defer srv.Close()

	if _, err := relay.Check(context.Background(), relay.NewDirect(srv.URL, "wrong")); err == nil {
		t.Fatal("a wrong key was accepted")
	}
}

func tcpListenerFor(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	return ln
}
