package relay_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"guts/exitnode"
	"guts/relay"
)

// legacyExit is a real exit node with the multiplexing marker stripped out of its answers: the
// phone sees exactly what it sees after an upgrade where the phone moved first.
func legacyExit(node *exitnode.Node) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := httptest.NewRecorder()
		node.ServeHTTP(rec, r)
		body := strings.Replace(rec.Body.String(), `,"info":"mux"`, "", 1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(rec.Code)
		_, _ = io.WriteString(w, body)
	})
}

func TestAnExitNodeWithoutMuxIsTold(t *testing.T) {
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"ok":true,"sid":"legacy"}`)
	}))
	defer stub.Close()

	m := relay.NewMux(relay.NewDirect(stub.URL, "k"), t.Logf)
	if _, err := m.Dial(context.Background(), "127.0.0.1", 9); !errors.Is(err, relay.ErrNoMux) {
		t.Fatalf("err = %v, want ErrNoMux", err)
	}
}

func TestALegacyExitNodeStillCarriesTraffic(t *testing.T) {
	payload := make([]byte, 64<<10)
	_, _ = rand.Read(payload)
	const connections = 3
	arrived := make(chan struct{}, connections)
	gate := make(chan struct{})
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Every request waits until all of them are in, so the sessions are counted while the
		// connections are open rather than after they closed.
		arrived <- struct{}{}
		<-gate
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		_, _ = w.Write(payload)
	}))
	defer web.Close()
	host, portStr, _ := net.SplitHostPort(web.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	node := exitnode.New("testkey")
	t.Cleanup(node.Close)
	exitSrv := httptest.NewServer(legacyExit(node))
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

	var wg sync.WaitGroup
	failures := make(chan string, connections)
	for i := 0; i < connections; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn := socksDial(t, addr, host, port)
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
			if _, err := io.WriteString(conn, "GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n"); err != nil {
				failures <- "write: " + err.Error()
				return
			}
			body, err := io.ReadAll(conn)
			if err != nil {
				failures <- "read: " + err.Error()
				return
			}
			idx := bytes.Index(body, []byte("\r\n\r\n"))
			if idx < 0 || !bytes.Equal(body[idx+4:], payload) {
				failures <- "payload differs: " + strconv.Itoa(len(body))
			}
		}()
	}
	// All requests are in flight now: a legacy exit node can only give each connection a session
	// of its own, which is the cost the phone must not hide from itself.
	for i := 0; i < connections; i++ {
		select {
		case <-arrived:
		case <-time.After(30 * time.Second):
			close(gate)
			t.Fatalf("only %d of %d requests arrived", i, connections)
		}
	}
	if live := node.LiveSessions(); live != connections {
		close(gate)
		t.Fatalf("the legacy exit node held %d sessions for %d connections, want %d", live, connections, connections)
	}
	close(gate)

	wg.Wait()
	close(failures)
	for message := range failures {
		t.Fatalf("%s", message)
	}
}
