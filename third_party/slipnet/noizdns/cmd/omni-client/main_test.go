package main

import (
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"noizdns/mobile"
)

func TestPrefixEndpoints(t *testing.T) {
	got := prefixEndpoints(" 1.1.1.1:853,tls://[2001:db8::1]:853 ", "tls://")
	want := "tls://1.1.1.1:853,tls://[2001:db8::1]:853"
	if got != want {
		t.Fatalf("prefixEndpoints() = %q, want %q", got, want)
	}
}

func TestParseAuthorityFlags(t *testing.T) {
	flags, err := parseAuthorityFlags("0,1,1", 3)
	if err != nil || len(flags) != 3 || flags[0] || !flags[1] || !flags[2] {
		t.Fatalf("parseAuthorityFlags() = %v, %v", flags, err)
	}
	if !hasMixedAuthority(flags) {
		t.Fatal("mixed flags were not detected")
	}
	if hasMixedAuthority([]bool{true, true}) {
		t.Fatal("uniform flags were detected as mixed")
	}
	for _, test := range []struct {
		raw   string
		count int
	}{
		{"0,1", 1}, {"0,x", 2}, {"0,,1", 3},
	} {
		if _, err := parseAuthorityFlags(test.raw, test.count); err == nil {
			t.Fatalf("parseAuthorityFlags(%q, %d) accepted invalid input", test.raw, test.count)
		}
	}
}

// TestMixedSOCKSNegotiation exercises the actual local composite path with
// two independent backend listeners. Only the winning backend receives the
// post-greeting byte stream; the other is used for fanout readiness only.
func TestMixedSOCKSNegotiation(t *testing.T) {
	addresses := make([]string, 2)
	listeners := make([]net.Listener, 2)
	for i := range listeners {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		listeners[i] = listener
		addresses[i] = listener.Addr().String()
		go func(listener net.Listener) {
			for {
				connection, err := listener.Accept()
				if err != nil {
					return
				}
				go func(connection net.Conn) {
					defer connection.Close()
					greeting := make([]byte, 3)
					if _, err := io.ReadFull(connection, greeting); err != nil {
						return
					}
					if _, err := connection.Write([]byte{5, 0}); err != nil {
						return
					}
					_, _ = io.Copy(connection, connection)
				}(connection)
			}
		}(listener)
	}
	defer func() {
		for _, listener := range listeners {
			listener.Close()
		}
	}()

	mixed := &mixedTunnel{
		options:   clientOptions{resolverMode: "fanout", rrSpread: 3},
		clients:   make([]*mobile.DnsttClient, 2),
		addresses: addresses,
	}
	proxySide, clientSide := net.Pipe()
	done := make(chan struct{})
	go func() {
		mixed.handle(proxySide)
		close(done)
	}()
	_ = clientSide.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := clientSide.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 2)
	if _, err := io.ReadFull(clientSide, response); err != nil {
		t.Fatal(err)
	}
	if response[0] != 5 || response[1] != 0 {
		t.Fatalf("SOCKS response = %v", response)
	}
	payload := []byte("mixed-resolver-payload")
	if _, err := clientSide.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(clientSide, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("echo = %q, want %q", got, payload)
	}
	clientSide.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("mixed relay did not stop after the client closed")
	}
}

// TestMixedRawTCPPreservesSSHBytes covers the chained DNSTT+SSH path. The
// composite must not perform a SOCKS greeting before JSch writes its SSH ID.
func TestMixedRawTCPPreservesSSHBytes(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		connection, err := listener.Accept()
		if err != nil {
			return
		}
		defer connection.Close()
		_, _ = io.Copy(connection, connection)
	}()
	mixed := &mixedTunnel{
		options:   clientOptions{resolverMode: "roundrobin", rrSpread: 1, rawTCP: true},
		clients:   make([]*mobile.DnsttClient, 1),
		addresses: []string{listener.Addr().String()},
	}
	proxySide, clientSide := net.Pipe()
	done := make(chan struct{})
	go func() {
		mixed.handle(proxySide)
		close(done)
	}()
	_ = clientSide.SetDeadline(time.Now().Add(5 * time.Second))
	payload := []byte("SSH-2.0-omni-test\\r\\n")
	if _, err := clientSide.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(clientSide, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("raw relay = %q, want %q", got, payload)
	}
	clientSide.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("raw relay did not stop after the client closed")
	}
}

func TestRawTCPCompositeAcceptsSingleResolver(t *testing.T) {
	tunnel, err := newMixedTunnel(
		clientOptions{
			domain: "t.example.com", publicKey: strings.Repeat("00", 32),
			listenAddr: "127.0.0.1:0", rawTCP: true,
		},
		[]string{"127.0.0.1:53"}, nil,
	)
	if err != nil {
		t.Fatalf("single-resolver raw TCP composite was rejected: %v", err)
	}
	if len(tunnel.clients) != 1 || len(tunnel.addresses) != 1 {
		t.Fatalf("raw TCP composite sizes = %d clients, %d addresses", len(tunnel.clients), len(tunnel.addresses))
	}
}

func TestMixedResolverLimitFailsBeforeAllocatingTunnels(t *testing.T) {
	endpoints := make([]string, maxMixedResolvers+1)
	flags := make([]bool, maxMixedResolvers+1)
	for i := range endpoints {
		endpoints[i] = "127.0.0.1:53"
		flags[i] = i%2 == 0
	}
	if _, err := newMixedTunnel(clientOptions{}, endpoints, flags); err == nil {
		t.Fatal("oversized mixed resolver list was accepted")
	}
}

func TestRoundRobinSelectionUsesSpread(t *testing.T) {
	mixed := &mixedTunnel{
		options: clientOptions{resolverMode: "roundrobin", rrSpread: 2},
		clients: make([]*mobile.DnsttClient, 3),
	}
	first := mixed.selectBackends()
	second := mixed.selectBackends()
	if len(first) != 2 || len(second) != 2 || first[0] != 0 || second[0] != 1 {
		t.Fatalf("round-robin selections = %v, %v", first, second)
	}
}
