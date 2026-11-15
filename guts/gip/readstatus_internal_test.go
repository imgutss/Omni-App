package gip

import (
	"net"
	"testing"
	"time"
)

// A throttled line can hand the status line over a byte at a time; the answer must still be
// recognised, or a working edge is reported silent.
func TestReadStatusSurvivesAStatusLineInPieces(t *testing.T) {
	client, server := net.Pipe()
	defer func() { _ = client.Close() }()
	go func() {
		defer func() { _ = server.Close() }()
		for _, b := range []byte("HTTP/1.1 302 Found\r\n") {
			if _, err := server.Write([]byte{b}); err != nil {
				return
			}
			time.Sleep(time.Millisecond)
		}
	}()
	if code := readStatus(client); code != 302 {
		t.Fatalf("status is %d, want 302", code)
	}
}

// Silence stays silence: a peer that sends a fragment and hangs up is not half an answer.
func TestReadStatusRejectsAFragment(t *testing.T) {
	client, server := net.Pipe()
	defer func() { _ = client.Close() }()
	go func() {
		_, _ = server.Write([]byte("HTT"))
		_ = server.Close()
	}()
	if code := readStatus(client); code != 0 {
		t.Fatalf("status is %d, want 0", code)
	}
}
