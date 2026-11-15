//go:build unix

package main

import (
	"fmt"
	"io"
	"net"
	"syscall"
	"time"
)

func protectSocketFD(address string, fd uintptr) error {
	deadline := time.Now().Add(5 * time.Second)
	connection, err := (&net.Dialer{Deadline: deadline}).Dial("unix", address)
	if err != nil {
		return err
	}
	defer connection.Close()
	unixConnection, ok := connection.(*net.UnixConn)
	if !ok {
		return fmt.Errorf("FD-control endpoint is not a Unix socket")
	}
	if err := unixConnection.SetDeadline(deadline); err != nil {
		return err
	}
	if _, _, err := unixConnection.WriteMsgUnix([]byte{1}, syscall.UnixRights(int(fd)), nil); err != nil {
		return err
	}
	status := []byte{0}
	if _, err := io.ReadFull(unixConnection, status); err != nil {
		return err
	}
	if status[0] != 1 {
		return fmt.Errorf("FD-control rejected the socket")
	}
	return nil
}
