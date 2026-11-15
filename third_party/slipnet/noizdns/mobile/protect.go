package mobile

// Omni's optional Android socket-protection hooks. An empty endpoint keeps
// upstream NoizDNS behavior; the Android wrapper sets it before Start.

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"syscall"
)

var fdControlUnixSocket string

// SetFDControlUnixSocket configures the host endpoint used to protect each
// upstream descriptor from being routed back into the Android VPN.
func SetFDControlUnixSocket(path string) { fdControlUnixSocket = path }

func protectControl(network, address string, rc syscall.RawConn) error {
	if fdControlUnixSocket == "" {
		return nil
	}
	var protectErr error
	if err := rc.Control(func(fd uintptr) { protectErr = protectFD(fd) }); err != nil {
		return err
	}
	return protectErr
}

func protectedDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	dialer := &net.Dialer{Control: protectControl}
	return dialer.DialContext(ctx, network, address)
}

func protectedListenUDP() (*net.UDPConn, error) {
	listenConfig := &net.ListenConfig{Control: protectControl}
	conn, err := listenConfig.ListenPacket(context.Background(), "udp", "")
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		conn.Close()
		return nil, fmt.Errorf("unexpected UDP packet connection type %T", conn)
	}
	return udpConn, nil
}

func protectedTLSDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	conn, err := protectedDialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		conn.Close()
		return nil, err
	}
	tlsConn := tls.Client(conn, &tls.Config{ServerName: host})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		conn.Close()
		return nil, err
	}
	return tlsConn, nil
}

// protectedDirectDialer is used below SOCKS5 as well as for direct connections.
type protectedDirectDialer struct{}

func (protectedDirectDialer) Dial(network, address string) (net.Conn, error) {
	return protectedDialContext(context.Background(), network, address)
}

func (protectedDirectDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return protectedDialContext(ctx, network, address)
}

func protectedHTTPTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = protectedDialContext
	return transport
}
