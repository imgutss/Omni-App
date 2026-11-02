package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

type streamDialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}

type confirmedStreamDialer interface {
	DialConfirmed(ctx context.Context, network, address string) (net.Conn, error)
}

var socksHandshakeTimeout = 15 * time.Second

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }

// serveSOCKS implements RFC 1928 CONNECT with the no-authentication method.
// It does not answer CONNECT successfully until Soar has opened the remote stream.
func serveSOCKS(parent context.Context, conn net.Conn, dialer streamDialer, dialTimeout time.Duration) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(socksHandshakeTimeout))
	reader := bufio.NewReader(conn)
	header, err := reader.Peek(2)
	if err != nil || header[0] != 5 || header[1] == 0 {
		return
	}
	if _, err = reader.Discard(2); err != nil {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err = io.ReadFull(reader, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		noAuth = noAuth || method == 0
	}
	if !noAuth {
		_, _ = conn.Write([]byte{5, 0xff})
		return
	}
	if _, err = conn.Write([]byte{5, 0}); err != nil {
		return
	}
	request := make([]byte, 4)
	if _, err = io.ReadFull(reader, request); err != nil || request[0] != 5 || request[2] != 0 {
		return
	}
	if request[1] != 1 {
		writeSOCKSReply(conn, 7)
		return
	}
	host, err := readSOCKSHost(reader, request[3])
	if err != nil {
		writeSOCKSReply(conn, 8)
		return
	}
	var portRaw [2]byte
	if _, err = io.ReadFull(reader, portRaw[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portRaw[:])
	if port == 0 {
		writeSOCKSReply(conn, 8)
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))
	// The 15-second bound above is only for the local SOCKS handshake. Leaving
	// it armed during a censored-network dial broke the connection before the
	// profile's own (normally 90-second) dial budget could finish.
	_ = conn.SetDeadline(time.Time{})
	ctx, cancel := context.WithTimeout(parent, dialTimeout)
	defer cancel()
	var remote net.Conn
	if confirmed, ok := dialer.(confirmedStreamDialer); ok {
		remote, err = confirmed.DialConfirmed(ctx, "tcp", target)
	} else {
		remote, err = dialer.DialContext(ctx, "tcp", target)
	}
	// Always leave enough time to return a definite SOCKS failure after a dial
	// timeout; otherwise clients see a reset instead of a usable error reply.
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if err != nil {
		writeSOCKSReply(conn, 5)
		return
	}
	defer remote.Close()
	if err = writeSOCKSReply(conn, 0); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	_ = remote.SetDeadline(time.Time{})
	local := &bufferedConn{Conn: conn, reader: reader}
	var once sync.Once
	closeBoth := func() {
		once.Do(func() {
			local.Close()
			remote.Close()
		})
	}
	go func() {
		defer closeBoth()
		_, _ = io.Copy(remote, local)
	}()
	_, _ = io.Copy(local, remote)
	closeBoth()
}

func readSOCKSHost(reader io.Reader, atyp byte) (string, error) {
	switch atyp {
	case 1:
		raw := make([]byte, net.IPv4len)
		if _, err := io.ReadFull(reader, raw); err != nil {
			return "", err
		}
		return net.IP(raw).String(), nil
	case 4:
		raw := make([]byte, net.IPv6len)
		if _, err := io.ReadFull(reader, raw); err != nil {
			return "", err
		}
		return net.IP(raw).String(), nil
	case 3:
		var size [1]byte
		if _, err := io.ReadFull(reader, size[:]); err != nil || size[0] == 0 {
			return "", errors.New("invalid SOCKS domain")
		}
		raw := make([]byte, int(size[0]))
		if _, err := io.ReadFull(reader, raw); err != nil {
			return "", err
		}
		host := string(raw)
		if net.ParseIP(host) == nil {
			for _, r := range host {
				if r <= 0x20 || r >= 0x7f || r == '/' || r == '\\' {
					return "", errors.New("invalid SOCKS domain")
				}
			}
		}
		return host, nil
	default:
		return "", fmt.Errorf("unsupported SOCKS address type %d", atyp)
	}
}

func writeSOCKSReply(conn net.Conn, status byte) error {
	_, err := conn.Write([]byte{5, status, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}
