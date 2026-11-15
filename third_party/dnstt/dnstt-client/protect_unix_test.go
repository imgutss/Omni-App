//go:build unix

package main

import (
	"fmt"
	"net"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

func startProtectStub(t *testing.T, status byte) (string, <-chan int) {
	t.Helper()
	path := fmt.Sprintf("/tmp/dnstt-protect-%d-%d.sock", os.Getpid(), time.Now().UnixNano())
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = listener.Close()
		_ = os.Remove(path)
	})
	counts := make(chan int, 2)
	go func() {
		conn, err := listener.AcceptUnix()
		if err != nil {
			return
		}
		defer conn.Close()
		payload := make([]byte, 1)
		oob := make([]byte, syscall.CmsgSpace(4))
		_, oobn, _, _, err := conn.ReadMsgUnix(payload, oob)
		if err != nil {
			counts <- -1
			return
		}
		messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
		count := 0
		if err == nil {
			for i := range messages {
				fds, e := syscall.ParseUnixRights(&messages[i])
				if e != nil {
					count = -1
					break
				}
				for _, fd := range fds {
					_ = syscall.Close(fd)
					count++
				}
			}
		} else {
			count = -1
		}
		counts <- count
		_, _ = conn.Write([]byte{status})
	}()
	return path, counts
}

func TestProtectControlPassesOneFD(t *testing.T) {
	path, counts := startProtectStub(t, 0x01)
	old := fdControlUnixSocket
	fdControlUnixSocket = path
	defer func() { fdControlUnixSocket = old }()

	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	raw, err := conn.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	if err := protectControl("udp", "", raw); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-counts:
		if got != 1 {
			t.Fatalf("got %d descriptors, want 1", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for protect request")
	}
}

func TestProtectFDReportsFailure(t *testing.T) {
	path, counts := startProtectStub(t, 0x00)
	old := fdControlUnixSocket
	fdControlUnixSocket = path
	defer func() { fdControlUnixSocket = old }()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	raw, err := conn.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var protectErr error
	if err := raw.Control(func(fd uintptr) { protectErr = protectFD(fd) }); err != nil {
		t.Fatal(err)
	}
	if protectErr == nil || !strings.Contains(protectErr.Error(), "0x00") {
		t.Fatalf("expected failure status, got %v", protectErr)
	}
	<-counts
}
