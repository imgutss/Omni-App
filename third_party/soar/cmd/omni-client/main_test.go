package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSettingsDefaultsAndExplicitFalse(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "settings.json")
	if err := os.WriteFile(path, []byte(`{"discovery":false,"transport":"only"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := readSettings(path)
	if err != nil {
		t.Fatal(err)
	}
	if s.Discovery || s.Transport != "only" || s.DiscoveryWant != 2 || s.MaxStreams != 128 {
		t.Fatalf("unexpected settings: %+v", s)
	}
	raw, _ := json.Marshal(s)
	var roundTrip settings
	if err := json.Unmarshal(raw, &roundTrip); err != nil {
		t.Fatal(err)
	}
	if roundTrip != s {
		t.Fatalf("settings did not round trip: %+v != %+v", roundTrip, s)
	}
}

func TestDecodePublicKeyForms(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i + 1)
	}
	for _, encoded := range []string{
		base64.RawURLEncoding.EncodeToString(key), base64.StdEncoding.EncodeToString(key),
		"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
	} {
		decoded, err := decodePublicKey(encoded)
		if err != nil || len(decoded) != 32 {
			t.Fatalf("decodePublicKey(%q): %v, %d", encoded, err, len(decoded))
		}
	}
	if _, err := decodePublicKey("c2hvcnQ="); err == nil {
		t.Fatal("accepted a short key")
	}
}

type recordingDialer struct {
	target string
	remote net.Conn
}

func (d *recordingDialer) DialContext(_ context.Context, network, target string) (net.Conn, error) {
	d.target = network + ":" + target
	return d.remote, nil
}

func TestSOCKSConnectPreservesDomainAndData(t *testing.T) {
	left, right := net.Pipe()
	dialer := &recordingDialer{remote: left}
	server, client := net.Pipe()
	defer client.Close()
	done := make(chan struct{})
	go func() {
		serveSOCKS(context.Background(), server, dialer, time.Second)
		close(done)
	}()
	reader := bufio.NewReader(client)
	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(reader, greeting); err != nil || greeting[1] != 0 {
		t.Fatalf("greeting: %v %v", greeting, err)
	}
	request := []byte{5, 1, 0, 3, 11}
	request = append(request, []byte("example.com")...)
	request = append(request, 0x01, 0xbb) // 443
	if _, err := client.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil || reply[1] != 0 {
		t.Fatalf("connect reply: %v %v", reply, err)
	}
	if dialer.target != "tcp:example.com:443" {
		t.Fatalf("wrong target %q", dialer.target)
	}
	// Verify buffered post-CONNECT bytes are not stranded in bufio.Reader.
	payload := []byte("ping")
	if _, err := client.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	_ = right.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.ReadFull(right, got); err != nil || string(got) != "ping" {
		t.Fatalf("payload: %q %v", got, err)
	}
	client.Close()
	left.Close()
	right.Close()
	<-done
}
