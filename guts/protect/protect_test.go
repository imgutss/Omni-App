package protect

import (
	"context"
	"fmt"
	"net"
	"os"
	"syscall"
	"testing"
	"time"
)

// appStub plays the part of the Android side: an abstract unix socket that receives one byte and
// one descriptor and answers 0x01, as CoreVpnService's protect server does.
type appStub struct {
	listener net.Listener
	fds      chan int
	clients  chan struct{}
	refuse   bool
}

func newAppStub(t *testing.T, name string, refuse bool) *appStub {
	t.Helper()
	listener, err := net.Listen("unix", name)
	if err != nil {
		t.Fatalf("listen %s: %v", name, err)
	}
	stub := &appStub{listener: listener, fds: make(chan int, 8), clients: make(chan struct{}, 8), refuse: refuse}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			stub.clients <- struct{}{}
			go stub.serve(conn)
		}
	}()
	t.Cleanup(func() {
		_ = listener.Close()
		_ = Use("")
	})
	return stub
}

func (s *appStub) serve(conn net.Conn) {
	defer conn.Close()
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return
	}
	payload := make([]byte, 4)
	out := make([]byte, syscall.CmsgSpace(4))
	_ = unixConn.SetDeadline(time.Now().Add(5 * time.Second))
	read, oobn, _, _, err := unixConn.ReadMsgUnix(payload, out)
	if err != nil || read != 1 || payload[0] != request || s.refuse {
		_, _ = unixConn.Write([]byte{0x00})
		return
	}
	messages, err := syscall.ParseSocketControlMessage(out[:oobn])
	if err != nil || len(messages) != 1 {
		_, _ = unixConn.Write([]byte{0x00})
		return
	}
	rights, err := syscall.ParseUnixRights(&messages[0])
	if err != nil || len(rights) != 1 {
		_, _ = unixConn.Write([]byte{0x00})
		return
	}
	// The app protects a socket that has not connected yet; the hook in net.Dialer runs there.
	if _, err := syscall.Getpeername(rights[0]); err == nil {
		_, _ = unixConn.Write([]byte{0x00})
		return
	}
	if _, err := syscall.GetsockoptInt(rights[0], syscall.SOL_SOCKET, syscall.SO_TYPE); err != nil {
		_, _ = unixConn.Write([]byte{0x00})
		return
	}
	s.fds <- rights[0]
	_, _ = unixConn.Write([]byte{granted})
}

func TestProtectedDialHandsTheSocketToTheAppBeforeConnecting(t *testing.T) {
	name := fmt.Sprintf("@guts-protect-test-%d-%d", os.Getpid(), t.Name()[0])
	stub := newAppStub(t, name, false)

	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("target listener: %v", err)
	}
	defer target.Close()
	accepted := make(chan struct{}, 1)
	go func() {
		conn, err := target.Accept()
		if err != nil {
			return
		}
		accepted <- struct{}{}
		_ = conn.Close()
	}()

	if err := Use(name); err != nil {
		t.Fatalf("Use: %v", err)
	}
	if Address() != name {
		t.Fatalf("Address = %q, want %q", Address(), name)
	}
	conn, err := Dial(context.Background(), "tcp", target.Addr().String())
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	countReceived := len(stub.clients)
	fd := <-stub.fds
	_ = syscall.Close(fd)
	conn.Close()

	if countReceived != 1 {
		t.Fatalf("the app saw %d protect requests, want 1", countReceived)
	}
	select {
	case <-accepted:
	default:
		// Accept may still be about to run; the descriptor checks above are what matter.
	}
}

func TestRefusedSocketFailsTheDial(t *testing.T) {
	name := fmt.Sprintf("@guts-protect-refused-%d-%d", os.Getpid(), 7)
	_ = newAppStub(t, name, true)
	if err := Use(name); err != nil {
		t.Fatalf("Use: %v", err)
	}
	if _, err := Dial(context.Background(), "tcp", "127.0.0.1:9"); err == nil {
		t.Fatal("Dial succeeded although the app refused to protect the socket")
	}
}

func TestNoServiceMeansAPlainDialer(t *testing.T) {
	if err := Use(""); err != nil {
		t.Fatalf("Use: %v", err)
	}
	if Address() != "" {
		t.Fatalf("Address = %q, want empty", Address())
	}
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("target listener: %v", err)
	}
	defer target.Close()
	conn, err := Dial(context.Background(), "tcp", target.Addr().String())
	if err != nil {
		t.Fatalf("plain Dial: %v", err)
	}
	_ = conn.Close()
}

func TestUseRejectsAnAddressThatIsNeitherAbstractNorAPath(t *testing.T) {
	if err := Use("omni-guts"); err == nil {
		t.Fatal("Use accepted a name that is not a unix socket address")
	}
}
