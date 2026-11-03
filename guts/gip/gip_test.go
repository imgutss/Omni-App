package gip_test

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"guts/gip"
)

// localServer serves TLS for the SNI a scan asks for, so a scan can be tested without Google.
func localServer(t *testing.T) (ip string, port int, roots *httptest.Server) {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "script.google.com" {
			w.WriteHeader(http.StatusMisdirectedRequest)
			return
		}
		w.WriteHeader(http.StatusFound)
	}))
	t.Cleanup(srv.Close)
	host, portStr, err := net.SplitHostPort(srv.Listener.Addr().String())
	if err != nil {
		t.Fatalf("addr: %v", err)
	}
	p, _ := strconv.Atoi(portStr)
	return host, p, srv
}

func TestProbeSeesAFrontedAnswer(t *testing.T) {
	host, port, srv := localServer(t)
	res := gip.Probe(context.Background(), net.JoinHostPort(host, strconv.Itoa(port)), gip.Options{
		SNI:       "example.com",
		FrontHost: "script.google.com",
		Timeout:   5 * time.Second,
		RootCAs:   srv.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs,
	})
	if res.Err != "" {
		t.Fatalf("probe failed: %s", res.Err)
	}
	if !res.Fronted {
		t.Fatalf("fronted answer not recognised, status %d", res.Status)
	}
	if res.Status != http.StatusFound {
		t.Fatalf("status is %d, want 302", res.Status)
	}
}

func TestProbeRejectsAnEdgeThatRefusesFronting(t *testing.T) {
	host, port, srv := localServer(t)
	res := gip.Probe(context.Background(), net.JoinHostPort(host, strconv.Itoa(port)), gip.Options{
		SNI:       "example.com",
		FrontHost: "other.example.com",
		Timeout:   5 * time.Second,
		RootCAs:   srv.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs,
	})
	if res.Fronted || res.Err == "" {
		t.Fatalf("a 421 edge was taken as usable: %+v", res)
	}
	if !strings.Contains(res.Err, "421") {
		t.Fatalf("error does not name the refusal: %q", res.Err)
	}
}

func TestCandidatesAreUniqueAndKeepTheExtrasFirst(t *testing.T) {
	ips := gip.Candidates(context.Background(), gip.Options{
		Extra: []string{"203.0.113.9"},
	})
	if len(ips) == 0 {
		t.Fatal("no candidates")
	}
	if ips[0] != "203.0.113.9" {
		t.Fatalf("the extra address is not first: %v", ips[0])
	}
	seen := map[string]bool{}
	for _, ip := range ips {
		if seen[ip] {
			t.Fatalf("duplicate candidate %s", ip)
		}
		seen[ip] = true
	}
}

func TestBestPrefersTheFastestFrontedAddress(t *testing.T) {
	results := []gip.Result{
		{IP: "10.0.0.1", RTTMs: 5, Err: "edge refuses fronting (421)"},
		{IP: "10.0.0.2", RTTMs: 40, Fronted: true},
		{IP: "10.0.0.3", RTTMs: 90, Fronted: true},
	}
	if got := gip.Best(results); got != "10.0.0.2" {
		t.Fatalf("best is %q", got)
	}
	if got := gip.Best([]gip.Result{{IP: "10.0.0.1", Err: "x"}}); got != "" {
		t.Fatalf("best of nothing is %q", got)
	}
}

func TestReachableStopsAtTheFirstWorkingEdge(t *testing.T) {
	host, port, srv := localServer(t)
	roots := srv.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs

	res, ok := gip.Reachable(context.Background(), gip.Options{
		SNI:       "example.com",
		FrontHost: "script.google.com",
		Timeout:   5 * time.Second,
		RootCAs:   roots,
		// A dead address first: the scan must walk past it rather than give up on the network.
		Only: []string{"127.0.0.1:1", net.JoinHostPort(host, strconv.Itoa(port))},
	})
	if !ok {
		t.Fatalf("a working edge was missed: %+v", res)
	}
	if res.Status != http.StatusFound {
		t.Fatalf("status = %d, want 302", res.Status)
	}
}

func TestReachableSaysNoWhenNothingAnswers(t *testing.T) {
	if _, ok := gip.Reachable(context.Background(), gip.Options{
		Timeout: 300 * time.Millisecond,
		Only:    []string{"127.0.0.1:1", "127.0.0.1:2"},
	}); ok {
		t.Fatal("a network with no edge was called reachable")
	}
}

func TestReachableHonoursTheDeadline(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	gip.Reachable(ctx, gip.Options{
		Timeout: 10 * time.Second,
		Only:    []string{"192.0.2.1:443", "192.0.2.2:443", "192.0.2.3:443"},
	})
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("the deadline was not honoured: %v", elapsed)
	}
}
