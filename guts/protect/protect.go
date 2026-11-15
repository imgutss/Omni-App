// Package protect protects the client's sockets from the VPN the client itself feeds.
//
// On a phone the default route is the tun device of our own VPN. The client sends its calls to a
// Google edge address, which the system would route straight back into that tun device: the tunnel
// would carry its own traffic, forever. Android's answer is VpnService.protect(fd), which binds the
// socket to the network under the VPN. A Go program cannot call it, so the app serves it over an
// abstract unix socket — the same protocol its DNS tunnel engines already use: one request byte and
// one SCM_RIGHTS descriptor, then one byte back, sent only after VpnService.protect accepted the
// socket. This package speaks that protocol from the Go side.
//
// The call happens in net.Dialer.Control, which runs after the socket exists and before it
// connects, so the socket is protected from its first packet. With no service address set, Dial is
// an ordinary dialer: that is what a desktop run and the tests use.
package protect

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	dialTimeout  = 12 * time.Second
	keepAlive    = 30 * time.Second
	answerBudget = 5 * time.Second
	request      = 0x01
	granted      = 0x01
)

// Dialer opens a connection the way the platform needs it: plain, or through the app's protect
// socket.
type Dialer func(ctx context.Context, network, address string) (net.Conn, error)

var (
	mu      sync.RWMutex
	active  Dialer = plainDial
	address string
)

func plainDial(ctx context.Context, network, target string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: dialTimeout, KeepAlive: keepAlive}
	return dialer.DialContext(ctx, network, target)
}

// Use makes every later connection go through the protect service at address, an abstract unix
// socket name such as "@omni-guts-4711-<session>"; an empty address restores the plain dialer. It
// is called once, at startup, before the client dials anything.
func Use(target string) error {
	target = strings.TrimSpace(target)
	if target == "" {
		set(plainDial, "")
		return nil
	}
	if !strings.HasPrefix(target, "@") && !strings.HasPrefix(target, "/") {
		return fmt.Errorf("guts: protect socket must be an abstract (@name) or a path, got %q", target)
	}
	set(socket{address: target}.dial, target)
	return nil
}

func set(dialer Dialer, target string) {
	mu.Lock()
	defer mu.Unlock()
	active = dialer
	address = target
}

// Address reports the protect service in use, empty when the plain dialer is.
func Address() string {
	mu.RLock()
	defer mu.RUnlock()
	return address
}

// Dial opens a connection with the dialer Use selected.
func Dial(ctx context.Context, network, target string) (net.Conn, error) {
	mu.RLock()
	dialer := active
	mu.RUnlock()
	return dialer(ctx, network, target)
}

type socket struct {
	address string
}

func (s socket) dial(ctx context.Context, network, target string) (net.Conn, error) {
	stray := s.address
	dialer := &net.Dialer{
		Timeout:   dialTimeout,
		KeepAlive: keepAlive,
		Control: func(name, addr string, conn syscall.RawConn) error {
			var protectErr error
			if err := conn.Control(func(fd uintptr) { protectErr = socket{address: stray}.protect(int(fd)) }); err != nil {
				return err
			}
			return protectErr
		},
	}
	return dialer.DialContext(ctx, network, target)
}

// protect hands one descriptor to the app and waits for its verdict. Each socket needs its own
// exchange: the app protects the descriptor it is given, not a process.
func (s socket) protect(fd int) error {
	conn, err := net.Dial("unix", s.address)
	if err != nil {
		return fmt.Errorf("guts: protect socket %s: %w", s.address, err)
	}
	defer conn.Close()
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return errors.New("guts: protect socket is not a unix connection")
	}
	_ = unixConn.SetDeadline(time.Now().Add(answerBudget))
	if _, _, err := unixConn.WriteMsgUnix([]byte{request}, syscall.UnixRights(fd), nil); err != nil {
		return fmt.Errorf("guts: send socket to protect: %w", err)
	}
	answer := make([]byte, 1)
	if _, err := io.ReadFull(unixConn, answer); err != nil {
		return fmt.Errorf("guts: protect answer: %w", err)
	}
	if answer[0] != granted {
		return errors.New("guts: the app refused to protect the socket")
	}
	return nil
}
