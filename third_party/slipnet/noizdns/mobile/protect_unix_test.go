//go:build unix

package mobile

import (
	"net"
	"syscall"
	"testing"
	"time"
)

func TestProtectControlSendsDescriptorAndRequiresAck(t *testing.T) {
	dir := t.TempDir()
	endpoint := dir + "/protect.sock"
	listener, err := net.Listen("unix", endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	serverDone := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverDone <- err
			return
		}
		defer conn.Close()
		unixConn := conn.(*net.UnixConn)
		_ = unixConn.SetDeadline(time.Now().Add(3 * time.Second))
		payload := make([]byte, 1)
		oob := make([]byte, syscall.CmsgSpace(4))
		_, oobn, _, _, err := unixConn.ReadMsgUnix(payload, oob)
		if err != nil {
			serverDone <- err
			return
		}
		messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
		if err != nil || len(messages) != 1 || payload[0] != 1 {
			serverDone <- err
			return
		}
		fds, err := syscall.ParseUnixRights(&messages[0])
		if err == nil {
			for _, fd := range fds {
				_ = syscall.Close(fd)
			}
		}
		if err == nil && len(fds) == 1 {
			_, err = unixConn.Write([]byte{1})
		}
		serverDone <- err
	}()

	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	raw, err := udp.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	old := fdControlUnixSocket
	fdControlUnixSocket = endpoint
	defer func() { fdControlUnixSocket = old }()
	if err := protectControl("udp", "127.0.0.1:53", raw); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-serverDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("protection handshake timed out")
	}
}

func TestProtectControlRejectsNegativeAck(t *testing.T) {
	endpoint := t.TempDir() + "/protect.sock"
	listener, err := net.Listen("unix", endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 1)
		oob := make([]byte, syscall.CmsgSpace(4))
		_, _, _, _, _ = conn.(*net.UnixConn).ReadMsgUnix(buf, oob)
		_, _ = conn.Write([]byte{0})
	}()
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	raw, err := udp.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	old := fdControlUnixSocket
	fdControlUnixSocket = endpoint
	defer func() { fdControlUnixSocket = old }()
	if err := protectControl("udp", "127.0.0.1:53", raw); err == nil {
		t.Fatal("negative protection acknowledgement was accepted")
	}
}
