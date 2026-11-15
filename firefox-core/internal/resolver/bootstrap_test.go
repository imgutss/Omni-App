package resolver

import (
	"context"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"
)

// The phone's own (carrier) DNS servers, installed by the app, are what the
// "system" fallback asks on Android where the engine cannot read the OS
// resolver. A nil resolver (system only) must answer through them.
func TestSystemServersRescueTheFallback(t *testing.T) {
	addr := startUDPDNS(t, [4]byte{93, 184, 216, 34})
	if err := SetSystemServers([]string{"udp://" + addr}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = SetSystemServers(nil) })

	var r *Resolver // the system resolver
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	res, err := r.Lookup(ctx, "vpn.mozilla.org")
	if err != nil {
		t.Fatalf("system fallback through carrier DNS: %v", err)
	}
	if res.Source != "system" || len(res.Addrs) == 0 || res.Addrs[0] != netip.MustParseAddr("93.184.216.34") {
		t.Fatalf("unexpected system result %+v", res)
	}
	if got := SystemServers(); len(got) != 1 {
		t.Fatalf("SystemServers() = %v", got)
	}
}

// A plain DNS answer that is Iran's filtering page (RFC1918) is rejected so a
// poisoned bootstrap cannot win; a real public answer is accepted.
func TestPlainFilterRejectsPoisonNotLoopback(t *testing.T) {
	if !allFilterAddrs([]netip.Addr{netip.MustParseAddr("10.10.34.34")}) {
		t.Fatal("Iran's filtering page must be rejected")
	}
	if allFilterAddrs([]netip.Addr{netip.MustParseAddr("127.0.0.1")}) {
		t.Fatal("loopback must not be rejected here (local test resolvers)")
	}
	if allFilterAddrs([]netip.Addr{netip.MustParseAddr("93.184.216.34")}) {
		t.Fatal("a public answer must be accepted")
	}

	poison := startUDPDNS(t, [4]byte{10, 10, 34, 34})
	r, err := New([]string{"udp://" + poison}, false)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := r.Lookup(ctx, "vpn.mozilla.org"); err == nil {
		t.Fatal("a poisoned plain answer must not resolve a public name")
	}
}

// With an encrypted server present, its answer wins over a plain one that
// would answer sooner: plain DNS is held back plainDNSGrace so a filtering
// network's plain answer cannot beat a real DoH answer.
func TestEncryptedAnswerWinsOverPlain(t *testing.T) {
	var hits atomic.Int32
	doh := startDoH(t, [4]byte{151, 101, 1, 69}, &hits)
	plain := startUDPDNS(t, [4]byte{151, 101, 2, 69})
	r, err := New([]string{doh, "udp://" + plain}, false)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	res, err := r.Lookup(ctx, "vpn.mozilla.org")
	if err != nil {
		t.Fatal(err)
	}
	if res.Source != doh {
		t.Fatalf("encrypted answer should win, got source %q (%+v)", res.Source, res)
	}
}

// isPlainServer distinguishes tamper-able UDP/TCP servers from DoH/DoT.
func TestIsPlainServer(t *testing.T) {
	plain, _ := parseServer("udp://8.8.8.8")
	tcp, _ := parseServer("tcp://8.8.8.8")
	dot, _ := parseServer("tls://1.1.1.1")
	doh, _ := parseServer("https://1.1.1.1/dns-query")
	if !isPlainServer(plain) || !isPlainServer(tcp) {
		t.Fatal("udp/tcp are plain")
	}
	if isPlainServer(dot) || isPlainServer(doh) {
		t.Fatal("DoT/DoH are encrypted")
	}
}
