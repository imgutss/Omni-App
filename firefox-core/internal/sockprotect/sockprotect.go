// Package sockprotect keeps this engine's own sockets outside the phone's VPN.
//
// On a phone the device's whole traffic goes into the app's VpnService and comes back to this process as
// SOCKS requests. The engine's own connections — to Mozilla's account, Guardian, the server list, and
// the Fastly proxy that carries the tunnel — must not go back into that same VpnService: it would hand
// them to this engine again, and every page would be carried by a tunnel inside a tunnel to Mozilla.
// Android's answer is VpnService.protect(fd), which only the app that owns the VpnService may call, so
// the app runs a protect server on an abstract Unix socket and this package hands every socket to it
// before the socket is connected or bound — the same handshake the other engines in this app use
// (see third_party/*/internal/sockprotect).
//
// Install it once at startup. From then on, a Control hook on the dialers in package egress and on the
// UDP listeners it opens protects every socket the engine uses to reach the network. A socket the app
// refuses is closed and the dial fails with a readable reason, rather than quietly going through the
// tunnel.
package sockprotect

import (
	"fmt"
	"io"
	"net"
	"sync/atomic"
	"syscall"
	"time"
)

// handshakeTimeout bounds one whole handshake — connect, send, status read — so a protect server that
// stopped answering cannot hang socket creation.
const handshakeTimeout = 5 * time.Second

// socketPath is the abstract Unix socket of the app's protect server; empty when none was installed.
var socketPath atomic.Value

// Install points every socket this process opens at the protect server on path. An empty path leaves
// sockets alone, which is what a desktop build wants.
func Install(path string) {
	if path == "" {
		socketPath.Store("")
		return
	}
	socketPath.Store(path)
}

// Enabled reports whether sockets are being protected.
func Enabled() bool {
	value, _ := socketPath.Load().(string)
	return value != ""
}

// ProtectFD hands fd to the app's protect server and waits for its verdict.
//
// The wire format matches the server the app runs for its other engines: one SCM_RIGHTS message carrying
// the descriptor and a single payload byte, answered by one status byte where 0x01 means the app
// protected the descriptor.
func ProtectFD(fd uintptr) error {
	value, _ := socketPath.Load().(string)
	if value == "" {
		return nil
	}
	deadline := time.Now().Add(handshakeTimeout)
	conn, err := (&net.Dialer{Deadline: deadline}).Dial("unix", value)
	if err != nil {
		return fmt.Errorf("connect to the protect server: %w", err)
	}
	defer conn.Close()

	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return fmt.Errorf("the protect server answered on a %T", conn)
	}
	if err := unixConn.SetDeadline(deadline); err != nil {
		return fmt.Errorf("set the protect deadline: %w", err)
	}
	if _, _, err := unixConn.WriteMsgUnix([]byte{0x01}, syscall.UnixRights(int(fd)), nil); err != nil {
		return fmt.Errorf("hand the socket to the protect server: %w", err)
	}
	var status [1]byte
	if _, err := io.ReadFull(unixConn, status[:]); err != nil {
		return fmt.Errorf("read the protect server's answer: %w", err)
	}
	if status[0] != 0x01 {
		return fmt.Errorf("the protect server refused the socket (status 0x%02x)", status[0])
	}
	return nil
}

// Control is the hook net.Dialer and net.ListenConfig take: it runs on a socket the kernel has made and
// not yet connected or bound, which is the moment the app can take it out of the VPN.
func Control(network, address string, c syscall.RawConn) error {
	if !Enabled() {
		return nil
	}
	var failure error
	if err := c.Control(func(fd uintptr) { failure = ProtectFD(fd) }); err != nil {
		return fmt.Errorf("inspect the socket: %w", err)
	}
	if failure != nil {
		return fmt.Errorf("protect %s %s: %w", network, address, failure)
	}
	return nil
}

// Compose runs both hooks: next first (which may bind the socket to an interface), then the protect
// handshake, so neither is skipped by the other.
func Compose(next func(network, address string, c syscall.RawConn) error) func(network, address string, c syscall.RawConn) error {
	if next == nil {
		return Control
	}
	return func(network, address string, c syscall.RawConn) error {
		if err := next(network, address, c); err != nil {
			return err
		}
		return Control(network, address, c)
	}
}

// control is the hook the engine's own dialers are built with: protected, and leaving through the
// network the app gave it (see package egress).
func control(next func(network, address string, c syscall.RawConn) error) func(network, address string, c syscall.RawConn) error {
	return Compose(next)
}
