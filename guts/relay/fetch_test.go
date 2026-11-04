package relay_test

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"io"
	"net/http"
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

type fakeGoogleDirect struct {
	mu       sync.Mutex
	method   string
	url      string
	host     string
	rangeHdr string
	cookie   string
	encoding string
	body     []byte
}

func (f *fakeGoogleDirect) Do(req *http.Request) (*http.Response, error) {
	body, err := io.ReadAll(req.Body)
	if err != nil {
		return nil, err
	}
	payload := "video-bytes"
	f.mu.Lock()
	f.method = req.Method
	f.url = req.URL.String()
	f.host = req.Host
	f.rangeHdr = req.Header.Get("Range")
	f.cookie = req.Header.Get("Cookie")
	f.encoding = req.Header.Get("Accept-Encoding")
	f.body = append([]byte(nil), body...)
	f.mu.Unlock()
	return &http.Response{
		Status:        "206 Partial Content",
		StatusCode:    http.StatusPartialContent,
		Proto:         "HTTP/2.0",
		ProtoMajor:    2,
		Header:        http.Header{"Content-Type": {"video/mp4"}, "Content-Range": {"bytes 0-10/11"}},
		Body:          io.NopCloser(strings.NewReader(payload)),
		ContentLength: int64(len(payload)),
		Request:       req,
	}, nil
}

func (f *fakeGoogleDirect) Describe() string { return "fake google direct" }

func (f *fakeGoogleDirect) snapshot() (string, string, string, string, string, string, []byte) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.method, f.url, f.host, f.rangeHdr, f.cookie, f.encoding, append([]byte(nil), f.body...)
}

func startFetchProxy(t *testing.T) (string, *fakeFetchDoor, *relay.CertificateAuthority) {
	t.Helper()
	return startFetchProxyWithGoogle(t, nil)
}

func startFetchProxyWithGoogle(t *testing.T, direct relay.GoogleDirectDoer) (string, *fakeFetchDoor, *relay.CertificateAuthority) {
	t.Helper()
	dir := t.TempDir()
	ca, err := relay.LoadOrCreateCA(filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key"))
	if err != nil {
		t.Fatalf("create CA: %v", err)
	}
	door := &fakeFetchDoor{}
	proxy := relay.NewProxy(door, "127.0.0.1:0", t.Logf)
	proxy.UseFetchMITM(ca)
	if direct != nil {
		proxy.UseGoogleDirect(direct)
	}
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

func TestFetchModeRoutesGoogleVideoDirectly(t *testing.T) {
	direct := &fakeGoogleDirect{}
	addr, door, ca := startFetchProxyWithGoogle(t, direct)
	host := "rr1---sn-abc.googlevideo.com"
	raw := socksDial(t, addr, host, 443)
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))

	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(ca.CertificatePEM()) {
		t.Fatal("the generated CA did not parse as a root")
	}
	tlsConn := tls.Client(raw, &tls.Config{
		RootCAs:    roots,
		ServerName: host,
		NextProtos: []string{"http/1.1"},
	})
	if err := tlsConn.Handshake(); err != nil {
		t.Fatalf("TLS handshake with local CA leaf: %v", err)
	}
	request := "POST /videoplayback?expire=1 HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Range: bytes=0-10\r\n" +
		"Cookie: SESSION=abc\r\n" +
		"Accept-Encoding: br\r\n" +
		"Origin: https://www.youtube.com\r\n" +
		"Content-Length: 7\r\n" +
		"Connection: close\r\n\r\nsegment"
	if _, err := io.WriteString(tlsConn, request); err != nil {
		t.Fatalf("write video request: %v", err)
	}
	response, err := io.ReadAll(tlsConn)
	if err != nil {
		t.Fatalf("read video response: %v", err)
	}
	text := string(response)
	if !strings.Contains(text, "206 Partial Content") || !strings.Contains(text, "Content-Range: bytes 0-10/11") || !strings.Contains(text, "video-bytes") {
		t.Fatalf("unexpected direct response:\n%s", text)
	}
	if got := door.request(); got.Op != "" {
		t.Fatalf("googlevideo reached the Apps Script door as %+v", got)
	}
	method, url, gotHost, rangeHdr, cookie, encoding, body := direct.snapshot()
	if method != "POST" || url != "https://"+host+"/videoplayback?expire=1" || gotHost != host {
		t.Fatalf("direct request = %s %q host %q", method, url, gotHost)
	}
	if rangeHdr != "bytes=0-10" || cookie != "SESSION=abc" || encoding != "br" || string(body) != "segment" {
		t.Fatalf("direct headers/body = range %q cookie %q encoding %q body %q", rangeHdr, cookie, encoding, body)
	}
}

func TestCheckFetchRejectsALegacyExitDoor(t *testing.T) {
	door := &fakeFetchDoor{info: "guts-exit/test"}
	if _, err := relay.CheckFetch(context.Background(), door); err == nil {
		t.Fatal("a legacy exit door passed the fetch check")
	}
}
