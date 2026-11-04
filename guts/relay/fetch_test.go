package relay_test

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"guts/proto"
	"guts/relay"
)

type fakeFetchDoor struct {
	mu   sync.Mutex
	last proto.Request
	info string
}

func (f *fakeFetchDoor) Call(_ context.Context, req proto.Request) (proto.Response, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.last = req
	if req.Op == proto.OpPing {
		info := f.info
		if info == "" {
			info = "guts-fetch/test fetch"
		}
		return proto.Response{OK: true, Info: info}, nil
	}
	if req.Op != proto.OpFetch {
		return proto.Response{Error: "unexpected op " + req.Op}, nil
	}
	body := "fetched " + req.Method + " " + req.URL
	return proto.Response{
		OK:     true,
		Status: 201,
		Headers: map[string][]string{
			"Content-Type": {"text/plain"},
			"X-Origin":     {"fake"},
		},
		D: base64.StdEncoding.EncodeToString([]byte(body)),
	}, nil
}

func (f *fakeFetchDoor) Describe() string { return "fake fetch door" }

func (f *fakeFetchDoor) request() proto.Request {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.last
}

func startFetchProxy(t *testing.T) (string, *fakeFetchDoor, *relay.CertificateAuthority) {
	t.Helper()
	dir := t.TempDir()
	ca, err := relay.LoadOrCreateCA(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatalf("create CA: %v", err)
	}
	door := &fakeFetchDoor{}
	proxy := relay.NewProxy(door, "127.0.0.1:0", t.Logf)
	proxy.UseFetchMITM(ca)
	addr, err := proxy.Listen()
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = proxy.Serve() }()
	t.Cleanup(func() { _ = proxy.Close() })
	return addr, door, ca
}

func TestFetchModePersistsTheLocalCA(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "ca.crt")
	keyPath := filepath.Join(dir, "ca.key")

	first, err := relay.LoadOrCreateCA(certPath, keyPath)
	if err != nil {
		t.Fatalf("create CA: %v", err)
	}
	second, err := relay.LoadOrCreateCA(certPath, keyPath)
	if err != nil {
		t.Fatalf("reload CA: %v", err)
	}
	if first.FingerprintSHA256() != second.FingerprintSHA256() {
		t.Fatal("reloading the CA changed its fingerprint")
	}
	info, err := os.Stat(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("CA key mode = %v, want 0600", info.Mode().Perm())
	}
}

func TestFetchModeCarriesPlainHTTP(t *testing.T) {
	addr, door, _ := startFetchProxy(t)
	conn := socksDial(t, addr, "example.com", 80)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	_, err := io.WriteString(conn, "POST /submit?q=1 HTTP/1.1\r\nHost: example.com\r\nX-Test: yes\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata")
	if err != nil {
		t.Fatalf("write request: %v", err)
	}
	raw, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	if !bytes.Contains(raw, []byte("201")) || !bytes.Contains(raw, []byte("fetched POST http://example.com/submit?q=1")) {
		t.Fatalf("unexpected response:\n%s", raw)
	}

	got := door.request()
	if got.Method != "POST" || got.URL != "http://example.com/submit?q=1" {
		t.Fatalf("door request = %+v", got)
	}
	if values := got.Headers["X-Test"]; len(values) != 1 || values[0] != "yes" {
		t.Fatalf("X-Test header = %v", values)
	}
	decoded, err := base64.StdEncoding.DecodeString(got.D)
	if err != nil || string(decoded) != "data" {
		t.Fatalf("door body = %q, err=%v", decoded, err)
	}
}

func TestFetchModeAnswersCorsPreflightLocally(t *testing.T) {
	addr, door, _ := startFetchProxy(t)
	conn := socksDial(t, addr, "example.com", 80)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	_, err := io.WriteString(conn, "OPTIONS /api HTTP/1.1\r\nHost: example.com\r\nOrigin: https://site.example\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: Authorization\r\nConnection: close\r\n\r\n")
	if err != nil {
		t.Fatalf("write preflight: %v", err)
	}
	raw, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read preflight: %v", err)
	}
	text := string(raw)
	if !strings.Contains(text, "204 No Content") || !strings.Contains(text, "Access-Control-Allow-Origin: https://site.example") {
		t.Fatalf("unexpected preflight response:\n%s", text)
	}
	if got := door.request(); got.Op != "" {
		t.Fatalf("preflight reached the door as %+v", got)
	}
}

func TestFetchModeTerminatesHTTPSWithTheLocalCA(t *testing.T) {
	addr, door, ca := startFetchProxy(t)
	raw := socksDial(t, addr, "example.com", 443)
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))

	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(ca.CertificatePEM()) {
		t.Fatal("the generated CA did not parse as a root")
	}
	tlsConn := tls.Client(raw, &tls.Config{
		RootCAs:    roots,
		ServerName: "example.com",
		NextProtos: []string{"http/1.1"},
	})
	if err := tlsConn.Handshake(); err != nil {
		t.Fatalf("TLS handshake with local CA leaf: %v", err)
	}
	if _, err := io.WriteString(tlsConn, "GET /secure HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatalf("write HTTPS request: %v", err)
	}
	response, err := io.ReadAll(tlsConn)
	if err != nil {
		t.Fatalf("read HTTPS response: %v", err)
	}
	if !strings.Contains(string(response), "fetched GET https://example.com/secure") {
		t.Fatalf("unexpected HTTPS response:\n%s", response)
	}
	if got := door.request(); got.URL != "https://example.com/secure" {
		t.Fatalf("door URL = %q", got.URL)
	}
}

func TestCheckFetchRejectsALegacyExitDoor(t *testing.T) {
	door := &fakeFetchDoor{info: "guts-exit/test"}
	if _, err := relay.CheckFetch(context.Background(), door); err == nil {
		t.Fatal("a legacy exit door passed the fetch check")
	}
}
