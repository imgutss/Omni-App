//go:build unix

// Package sockprotect hands new upstream socket descriptors to Android's
// VpnService protection endpoint before connect or bind.
package sockprotect

import (
	"fmt"
	"io"
	"net"
	"syscall"
	"time"
)

const protectTimeout = 5 * time.Second

func ProtectFD(path string, fd uintptr) error {
	deadline := time.Now().Add(protectTimeout)
	conn, err := (&net.Dialer{Deadline: deadline}).Dial("unix", path)
	if err != nil {
		return fmt.Errorf("connect protect socket: %w", err)
	}
	defer conn.Close()
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return fmt.Errorf("unexpected protect socket type %T", conn)
	}
	if err := unixConn.SetDeadline(deadline); err != nil {
		return err
	}
	if _, _, err := unixConn.WriteMsgUnix([]byte{1}, syscall.UnixRights(int(fd)), nil); err != nil {
		return fmt.Errorf("send fd to protect socket: %w", err)
	}
	var status [1]byte
	if _, err := io.ReadFull(unixConn, status[:]); err != nil {
		return fmt.Errorf("read protect status: %w", err)
	}
	if status[0] != 1 {
		return fmt.Errorf("protect server returned status 0x%02x", status[0])
	}
	return nil
}

func Control(path string) func(network, address string, raw syscall.RawConn) error {
	return func(network, address string, raw syscall.RawConn) error {
		if path == "" {
			return nil
		}
		var protectErr error
		if err := raw.Control(func(fd uintptr) { protectErr = ProtectFD(path, fd) }); err != nil {
			return fmt.Errorf("socket control failed for %s %s: %w", network, address, err)
		}
		if protectErr != nil {
			return fmt.Errorf("protect upstream socket %s %s: %w", network, address, protectErr)
		}
		return nil
	}
}
