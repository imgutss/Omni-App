package main

import (
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// serveSocksAuthBridge accepts no-auth SOCKS5 locally and performs the real
// remote SOCKS5 negotiation, with optional username/password authentication,
// over the raw VayDNS stream. A successful local greeting therefore proves
// that the tunnel and remote SOCKS service answered.
func serveSocksAuthBridge(publicAddr, rawAddr, username, password string) error {
	listener, err := net.Listen("tcp", publicAddr)
	if err != nil {
		return err
	}
	defer listener.Close()
	for {
		client, err := listener.Accept()
		if err != nil {
			return err
		}
		go func() {
			defer client.Close()
			_ = handleSocksAuthClient(client, rawAddr, username, password)
		}()
	}
}

func handleSocksAuthClient(client net.Conn, rawAddr, username, password string) error {
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))
	greeting, err := readSocksGreeting(client)
	if err != nil || !supportsNoAuth(greeting) {
		_ = client.Close()
		return fmt.Errorf("local client does not offer SOCKS5 no-auth")
	}

	raw, err := dialRawWithRetry(rawAddr, 20*time.Second)
	if err != nil {
		return err
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(30 * time.Second))

	// Offer both methods remotely; the remote service decides which one it requires.
	if _, err := raw.Write([]byte{5, 2, 0, 2}); err != nil {
		return err
	}
	choice := make([]byte, 2)
	if _, err := io.ReadFull(raw, choice); err != nil {
		return err
	}
	if choice[0] != 5 {
		return fmt.Errorf("invalid remote SOCKS version")
	}
	switch choice[1] {
	case 0:
	case 2:
		auth := []byte{1, byte(len(username))}
		auth = append(auth, username...)
		auth = append(auth, byte(len(password)))
		auth = append(auth, password...)
		if _, err := raw.Write(auth); err != nil {
			return err
		}
		response := make([]byte, 2)
		if _, err := io.ReadFull(raw, response); err != nil {
			return err
		}
		if response[0] != 1 || response[1] != 0 {
			return fmt.Errorf("remote SOCKS authentication failed")
		}
	default:
		_, _ = client.Write([]byte{5, 0xff})
		return fmt.Errorf("remote SOCKS rejected authentication methods")
	}
	if _, err := client.Write([]byte{5, 0}); err != nil {
		return err
	}

	request, err := readSocksControlMessage(client)
	if err != nil {
		return err
	}
	if len(request) < 5 || request[1] != 1 {
		_, _ = client.Write(socksReply(7))
		return fmt.Errorf("only SOCKS CONNECT is supported")
	}
	if _, err := raw.Write(request); err != nil {
		return err
	}
	response, err := readSocksControlMessage(raw)
	if err != nil {
		return err
	}
	if _, err := client.Write(response); err != nil {
		return err
	}
	if response[1] != 0 {
		return fmt.Errorf("remote CONNECT failed: %d", response[1])
	}

	_ = client.SetDeadline(time.Time{})
	_ = raw.SetDeadline(time.Time{})
	relaySocks(client, raw)
	return nil
}

func readSocksGreeting(reader io.Reader) ([]byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	if header[0] != 5 || header[1] == 0 {
		return nil, fmt.Errorf("invalid SOCKS greeting")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(reader, methods); err != nil {
		return nil, err
	}
	return methods, nil
}

func supportsNoAuth(methods []byte) bool {
	for _, method := range methods {
		if method == 0 {
			return true
		}
	}
	return false
}

func readSocksControlMessage(reader io.Reader) ([]byte, error) {
	message := make([]byte, 4)
	if _, err := io.ReadFull(reader, message); err != nil {
		return nil, err
	}
	if message[0] != 5 {
		return nil, fmt.Errorf("invalid SOCKS control message")
	}
	remaining := 0
	switch message[3] {
	case 1:
		remaining = 4
	case 4:
		remaining = 16
	case 3:
		length := []byte{0}
		if _, err := io.ReadFull(reader, length); err != nil {
			return nil, err
		}
		message = append(message, length[0])
		remaining = int(length[0])
	default:
		return nil, fmt.Errorf("invalid SOCKS address type")
	}
	remaining += 2
	tail := make([]byte, remaining)
	if _, err := io.ReadFull(reader, tail); err != nil {
		return nil, err
	}
	return append(message, tail...), nil
}

func socksReply(code byte) []byte {
	return []byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0}
}

func dialRawWithRetry(address string, budget time.Duration) (net.Conn, error) {
	deadline := time.Now().Add(budget)
	var last error
	for {
		conn, err := net.DialTimeout("tcp", address, 750*time.Millisecond)
		if err == nil {
			return conn, nil
		}
		last = err
		if time.Now().After(deadline) {
			return nil, last
		}
		time.Sleep(150 * time.Millisecond)
	}
}

func relaySocks(left, right net.Conn) {
	var once sync.Once
	closeBoth := func() {
		_ = left.Close()
		_ = right.Close()
	}
	defer once.Do(closeBoth)
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(right, left)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(left, right)
		done <- struct{}{}
	}()
	<-done
	once.Do(closeBoth)
}
