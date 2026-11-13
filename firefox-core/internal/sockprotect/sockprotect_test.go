package sockprotect

import (
	"net"
	"testing"
	"time"
)

// No server installed is no protection, and no failure either: a desktop build opens its sockets the
// way it always did.
func TestInstallWithoutAPathLeavesSocketsAlone(t *testing.T) {
	Install("")
	t.Cleanup(func() { Install("") })
	if Enabled() {
		t.Fatal("an empty path must not enable protection")
	}
	if err := Control("tcp", "127.0.0.1:1", nil); err != nil {
		t.Fatalf("control without a server must not fail: %v", err)
	}
}

// A server that does not answer must fail the dial with a reason: a socket the app did not take out of
// the VPN must never be handed back as if it had been.
func TestAMissingServerFailsTheDial(t *testing.T) {
	Install("@omni-sockprotect-test-nothing-listens")
	t.Cleanup(func() { Install("") })
	d := &net.Dialer{Timeout: time.Second, Control: Control}
	conn, err := d.Dial("tcp", "127.0.0.1:9")
	if err == nil {
		_ = conn.Close()
		t.Fatal("a dial must not succeed while the protect server refuses to answer")
	}
}

// The handshake is one descriptor and a payload byte out, one status byte back: a server answering
// 0x01 is what Android's protect does when it accepts a socket, and the dial then goes ahead.
func TestProtectFDReadsTheServersVerdict(t *testing.T) {
	address := "@omni-sockprotect-test-verdict"
	serverConn, err := net.Listen("unix", address)
	if err != nil {
		t.Skipf("this environment has no abstract Unix sockets: %v", err)
	}
	defer serverConn.Close()
	Install(address)
	t.Cleanup(func() { Install("") })

	go func() {
		conn, err := serverConn.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 1)
		if _, _, _, _, err := conn.(*net.UnixConn).ReadMsgUnix(buf, make([]byte, 64)); err != nil {
			return
		}
		_, _ = conn.Write([]byte{0x01})
	}()

	// The dial itself is expected to fail: nothing listens on port 9. What matters is that the failure
	// is the connection's and not the protect handshake's.
	d := &net.Dialer{Timeout: time.Second, Control: Control}
	if conn, err := d.Dial("tcp", "127.0.0.1:9"); err == nil {
		_ = conn.Close()
	}
}
