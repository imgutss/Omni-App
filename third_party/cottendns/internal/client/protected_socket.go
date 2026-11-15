package client

import (
	"context"
	"fmt"
	"net"
	"syscall"

	"cottendns-go/internal/sockprotect"
)

// protectControl runs before every upstream resolver connect/bind. Local SOCKS
// and local DNS listeners deliberately do not use it: Android protects only the
// sockets that must bypass the VPN whose traffic this process carries.
func (c *Client) protectControl(network, address string, raw syscall.RawConn) error {
	path := c.cfg.FDControlUnixSocket
	if path == "" {
		return nil
	}
	var protectErr error
	if err := raw.Control(func(fd uintptr) { protectErr = sockprotect.ProtectFD(path, fd) }); err != nil {
		return fmt.Errorf("socket control failed for %s %s: %w", network, address, err)
	}
	if protectErr != nil {
		return fmt.Errorf("protect upstream socket %s %s: %w", network, address, protectErr)
	}
	return nil
}

func (c *Client) dialUDPResolver(ctx context.Context, resolverLabel string) (*net.UDPConn, error) {
	dialer := net.Dialer{Control: c.protectControl}
	conn, err := dialer.DialContext(ctx, "udp", resolverLabel)
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("unexpected UDP resolver connection type %T", conn)
	}
	return udpConn, nil
}

func (c *Client) listenUDPProtected(ctx context.Context, network, address string) (*net.UDPConn, error) {
	packetConn, err := (&net.ListenConfig{Control: c.protectControl}).ListenPacket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	udpConn, ok := packetConn.(*net.UDPConn)
	if !ok {
		_ = packetConn.Close()
		return nil, fmt.Errorf("unexpected UDP listener type %T", packetConn)
	}
	return udpConn, nil
}

// openTunnelSocketFamily is the UDP data-plane counterpart of the legacy
// upstream helper; the package-level helper remains unchanged for unit tests.
func (c *Client) openTunnelSocketFamily(network string, workers int) ([]*net.UDPConn, error) {
	conns := make([]*net.UDPConn, 0, workers)
	bind := "0.0.0.0:0"
	if network == "udp6" {
		bind = "[::]:0"
	}
	for i := 0; i < workers; i++ {
		conn, err := c.listenUDPProtected(context.Background(), network, bind)
		if err != nil {
			for _, opened := range conns {
				_ = opened.Close()
			}
			return nil, err
		}
		conns = append(conns, conn)
	}
	return conns, nil
}
