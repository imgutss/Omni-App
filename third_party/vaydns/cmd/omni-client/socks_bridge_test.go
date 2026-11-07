package main

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
)

func TestSocksAuthBridgePerformsRemoteAuthenticationAndConnect(t *testing.T) {
	raw := listenTestTCP(t)
	defer raw.Close()
	go func() {
		connection, err := raw.Accept()
		if err != nil {
			return
		}
		defer connection.Close()
		greeting := make([]byte, 4)
		_, _ = io.ReadFull(connection, greeting)
		if !bytes.Equal(greeting, []byte{5, 2, 0, 2}) {
			return
		}
		_, _ = connection.Write([]byte{5, 2})
		expectedAuth := append([]byte{1, 4}, []byte("user")...)
		expectedAuth = append(expectedAuth, 4)
		expectedAuth = append(expectedAuth, []byte("pass")...)
		auth := make([]byte, len(expectedAuth))
		_, _ = io.ReadFull(connection, auth)
		if !bytes.Equal(auth, expectedAuth) {
			return
		}
		_, _ = connection.Write([]byte{1, 0})
		request, err := readSocksControlMessage(connection)
		if err != nil || len(request) == 0 || request[1] != 1 {
			return
		}
		_, _ = connection.Write(socksReply(0))
		payload := make([]byte, 4)
		_, _ = io.ReadFull(connection, payload)
		_, _ = connection.Write(payload)
	}()

	public := listenTestTCP(t)
	publicAddr := public.Addr().String()
	_ = public.Close()
	go func() { _ = serveSocksAuthBridge(publicAddr, raw.Addr().String(), "user", "pass") }()

	var client net.Conn
	var err error
	for i := 0; i < 30; i++ {
		client, err = net.DialTimeout("tcp", publicAddr, 100*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = client.Write([]byte{5, 1, 0})
	choice := make([]byte, 2)
	if _, err := io.ReadFull(client, choice); err != nil || !bytes.Equal(choice, []byte{5, 0}) {
		t.Fatalf("local auth choice = %v, %v", choice, err)
	}
	request := []byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 80}
	_, _ = client.Write(request)
	response := make([]byte, 10)
	if _, err := io.ReadFull(client, response); err != nil || response[1] != 0 {
		t.Fatalf("CONNECT response = %v, %v", response, err)
	}
	_, _ = client.Write([]byte("ping"))
	echo := make([]byte, 4)
	if _, err := io.ReadFull(client, echo); err != nil || string(echo) != "ping" {
		t.Fatalf("relay = %q, %v", echo, err)
	}
}

func listenTestTCP(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return listener
}
