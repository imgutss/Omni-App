package main

// Optional Android socket-protection hooks. With no -fd-control-unix-socket
// flag these retain the upstream client's ordinary network behavior.

import (
	"context"
	"net"
	"net/http"
	"syscall"
)

var fdControlUnixSocket string

// protectControl is called before an upstream socket is connected or bound.
func protectControl(network, address string, rc syscall.RawConn) error {
	if fdControlUnixSocket == "" {
		return nil
	}
	var protectErr error
	if err := rc.Control(func(fd uintptr) {
		protectErr = protectFD(fd)
	}); err != nil {
		return err
	}
	return protectErr
}

func protectedDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	dialer := &net.Dialer{Control: protectControl}
	return dialer.DialContext(ctx, network, address)
}

// protectedHTTPTransport is used for both ordinary DoH and the plain-HTTP
// branch of the uTLS RoundTripper. ProxyFromEnvironment is deliberately off,
// matching the upstream client's direct-resolver behavior.
func protectedHTTPTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = protectedDialContext
	return transport
}
