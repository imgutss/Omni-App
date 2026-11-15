package controlplane

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

// When the direct path fails at the transport layer (the name cannot be
// resolved / the connection is reset — the Irancell bootstrap failure), Do
// retries the request through the installed bootstrap fallback instead of
// returning the transport error and killing the whole chain.
func TestDoRecoversTransportFailureThroughBootstrap(t *testing.T) {
	previous := Transport()
	// A transport that always fails at the transport layer, like a DNS
	// failure: no HTTP response exists.
	SetTransport(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, io.ErrUnexpectedEOF
	}))
	t.Cleanup(func() { SetTransport(previous) })

	called := false
	SetBootstrapFallback(func(req *http.Request) (*http.Response, error) {
		called = true
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("recovered")),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	t.Cleanup(func() { SetBootstrapFallback(nil) })

	req, _ := http.NewRequest(http.MethodGet, "https://vpn.mozilla.org/api/v1/fpn/token", nil)
	resp, err := Do(req)
	if err != nil || resp == nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("bootstrap recovery: resp=%v err=%v", resp, err)
	}
	if !called {
		t.Fatal("the bootstrap fallback was not called on a transport failure")
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "recovered" {
		t.Fatalf("body = %q", body)
	}
}

// An HTTP response (even an error status) is the server's own answer and is
// NOT recovered: only transport failures are.
func TestDoDoesNotRecoverAnHTTPAnswer(t *testing.T) {
	previous := Transport()
	SetTransport(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusUnavailableForLegalReasons,
			Body:       io.NopCloser(strings.NewReader("451")),
			Header:     make(http.Header),
		}, nil
	}))
	t.Cleanup(func() { SetTransport(previous) })

	called := false
	SetBootstrapFallback(func(*http.Request) (*http.Response, error) {
		called = true
		return nil, nil
	})
	t.Cleanup(func() { SetBootstrapFallback(nil) })

	req, _ := http.NewRequest(http.MethodGet, "https://vpn.mozilla.org/api/v1/fpn/token", nil)
	resp, err := Do(req)
	if err != nil || resp == nil || resp.StatusCode != http.StatusUnavailableForLegalReasons {
		t.Fatalf("an HTTP answer must pass through: resp=%v err=%v", resp, err)
	}
	resp.Body.Close()
	if called {
		t.Fatal("the bootstrap fallback must not fire on an HTTP response")
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
