package main

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestReadResolversCanonicalForms(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resolvers.txt")
	input := "# synthetic documentation addresses\n192.0.2.1\n192.0.2.1:53\n2001:db8::1\n[2001:db8::1]:5353\n198.51.100.2:5353\n"
	if err := os.WriteFile(path, []byte(input), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := readResolvers(path)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"192.0.2.1:53", "[2001:db8::1]:53", "[2001:db8::1]:5353", "198.51.100.2:5353"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("resolvers = %q, want %q", got, want)
	}
}

func TestReadResolversRejectsMalformedEntries(t *testing.T) {
	for _, input := range []string{"dns.example\n", "192.0.2.1:0\n", "192.0.2.1:53:90\n", "192.0.2.1\x00\n"} {
		path := filepath.Join(t.TempDir(), "resolvers.txt")
		if err := os.WriteFile(path, []byte(input), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := readResolvers(path); err == nil {
			t.Fatalf("accepted malformed resolver %q", input)
		}
	}
}

func TestSettingsCountryDefaultAndCanonicalization(t *testing.T) {
	for _, test := range []struct {
		json string
		want string
	}{{`{}`, "IR"}, {`{"country":" ir "}`, "IR"}, {`{"country":""}`, ""}} {
		path := filepath.Join(t.TempDir(), "settings.json")
		if err := os.WriteFile(path, []byte(test.json), 0o600); err != nil {
			t.Fatal(err)
		}
		got, err := readSettings(path)
		if err != nil {
			t.Fatal(err)
		}
		if got.Country != test.want {
			t.Fatalf("country for %s = %q, want %q", test.json, got.Country, test.want)
		}
	}
}

type delayedDialer struct {
	remote net.Conn
	delay  time.Duration
}

func (d *delayedDialer) DialContext(ctx context.Context, _, _ string) (net.Conn, error) {
	timer := time.NewTimer(d.delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
		return d.remote, nil
	}
}

func TestSOCKSClearsHandshakeDeadlineBeforeTunnelDial(t *testing.T) {
	oldTimeout := socksHandshakeTimeout
	socksHandshakeTimeout = 40 * time.Millisecond
	t.Cleanup(func() { socksHandshakeTimeout = oldTimeout })

	remoteServer, remoteClient := net.Pipe()
	defer remoteServer.Close()
	defer remoteClient.Close()
	server, client := net.Pipe()
	defer client.Close()
	dialer := &delayedDialer{remote: remoteClient, delay: 3 * socksHandshakeTimeout}
	done := make(chan struct{})
	go func() {
		serveSOCKS(context.Background(), server, dialer, time.Second)
		close(done)
	}()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	reader := bufio.NewReader(client)
	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(reader, greeting); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write([]byte{5, 1, 0, 1, 192, 0, 2, 10, 0, 80}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil || reply[1] != 0 {
		t.Fatalf("delayed CONNECT reply = %v, %v", reply, err)
	}
	_ = client.Close()
	_ = remoteServer.Close()
	<-done
}

type confirmedDialer struct{ remote net.Conn }

func (d *confirmedDialer) DialContext(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("unconfirmed dial must not be used")
}

func (d *confirmedDialer) DialConfirmed(context.Context, string, string) (net.Conn, error) {
	return d.remote, nil
}

func TestSOCKSPrefersRemoteOpenConfirmation(t *testing.T) {
	remoteServer, remoteClient := net.Pipe()
	defer remoteServer.Close()
	defer remoteClient.Close()
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		serveSOCKS(context.Background(), server, &confirmedDialer{remote: remoteClient}, time.Second)
		close(done)
	}()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	reader := bufio.NewReader(client)
	_, _ = client.Write([]byte{5, 1, 0})
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(reader, greeting); err != nil {
		t.Fatal(err)
	}
	_, _ = client.Write([]byte{5, 1, 0, 1, 192, 0, 2, 10, 0, 80})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil || reply[1] != 0 {
		t.Fatalf("confirmed CONNECT reply = %v, %v", reply, err)
	}
	_ = client.Close()
	_ = remoteServer.Close()
	<-done
}
