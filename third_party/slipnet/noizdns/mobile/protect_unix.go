//go:build unix

package mobile

import (
	"fmt"
	"io"
	"net"
	"syscall"
	"time"
)

const protectTimeout = 5 * time.Second

// protectFD sends one descriptor and waits for the Android host's verdict.
// One payload byte plus SCM_RIGHTS is sent; 0x01 means protected.
func protectFD(fd uintptr) error {
	deadline := time.Now().Add(protectTimeout)
	conn, err := (&net.Dialer{Deadline: deadline}).Dial("unix", fdControlUnixSocket)
	if err != nil {
		return fmt.Errorf("connect protect socket: %w", err)
	}
	defer conn.Close()
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return fmt.Errorf("unexpected protect socket type %T", conn)
	}
	if err := unixConn.SetDeadline(deadline); err != nil {
		return fmt.Errorf("set protect deadline: %w", err)
	}
	if _, _, err := unixConn.WriteMsgUnix([]byte{0x01}, syscall.UnixRights(int(fd)), nil); err != nil {
		return fmt.Errorf("send fd to protect socket: %w", err)
	}
	var status [1]byte
	if _, err := io.ReadFull(unixConn, status[:]); err != nil {
		return fmt.Errorf("read protect status: %w", err)
	}
	if status[0] != 0x01 {
		return fmt.Errorf("protect server returned status 0x%02x", status[0])
	}
	return nil
}
