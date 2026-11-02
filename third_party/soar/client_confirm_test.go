package soar

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"net"
	"testing"
	"time"
)

func TestDialConfirmedWaitsForRemoteOpenAndFailure(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dns, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); _ = dns.Close() })
	go func() {
		_ = Serve(ctx, dns, ServerConfig{Zone: "t.example.com", PrivateKey: priv, Dial: (&net.Dialer{}).DialContext})
	}()
	client, err := NewClient(Config{
		Zone: "t.example.com", ServerKey: pub, Resolvers: []string{dns.LocalAddr().String()},
		Discovery: Discovery{Enabled: false},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })

	origin, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	dialCtx, stop := context.WithTimeout(ctx, 15*time.Second)
	defer stop()
	confirmed, err := client.DialConfirmed(dialCtx, "tcp", origin.Addr().String())
	if err != nil {
		t.Fatalf("confirmed remote open failed: %v", err)
	}
	_ = confirmed.Close()

	refused, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	refusedAddress := refused.Addr().String()
	_ = refused.Close()
	unconfirmed, err := client.DialContext(dialCtx, "tcp", refusedAddress)
	if err != nil {
		t.Fatalf("local queueing should not require remote readiness: %v", err)
	}
	_ = unconfirmed.Close()
	if remote, err := client.DialConfirmed(dialCtx, "tcp", refusedAddress); err == nil {
		_ = remote.Close()
		t.Fatal("DialConfirmed reported success for a refused remote destination")
	}
}
