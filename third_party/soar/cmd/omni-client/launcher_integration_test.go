package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/getlantern/soar"
)

// Exercises the production SOCKS adapter through a real Soar UDP DNS session and
// a real TCP origin. This is host loopback evidence, not an Android VPN claim.
func TestSOCKSThroughRealSoarLoopback(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dns, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); dns.Close() })
	go func() {
		_ = soar.Serve(ctx, dns, soar.ServerConfig{
			Zone: "t.example.com", PrivateKey: priv, Dial: (&net.Dialer{}).DialContext,
		})
	}()

	origin, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { origin.Close() })
	go func() {
		for {
			conn, err := origin.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
		}
	}()

	client, err := soar.NewClient(soar.Config{
		Zone: "t.example.com", ServerKey: pub, Resolvers: []string{dns.LocalAddr().String()},
		Discovery: soar.Discovery{Enabled: false},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	serverSide, socks := net.Pipe()
	defer socks.Close()
	done := make(chan struct{})
	go func() {
		serveSOCKS(ctx, serverSide, client, 20*time.Second)
		close(done)
	}()
	_ = socks.SetDeadline(time.Now().Add(30 * time.Second))
	reader := bufio.NewReader(socks)
	if _, err = socks.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err = io.ReadFull(reader, greeting); err != nil || greeting[1] != 0 {
		t.Fatalf("SOCKS greeting failed: %v %v", greeting, err)
	}
	host, portText, err := net.SplitHostPort(origin.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	request := []byte{5, 1, 0, 1}
	request = append(request, net.ParseIP(host).To4()...)
	var portRaw [2]byte
	binary.BigEndian.PutUint16(portRaw[:], uint16(port))
	request = append(request, portRaw[:]...)
	if _, err = socks.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err = io.ReadFull(reader, reply); err != nil || reply[1] != 0 {
		t.Fatalf("real tunnel CONNECT failed: %v %v", reply, err)
	}
	payload := []byte("omni-soar-real-loopback")
	if _, err = socks.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err = io.ReadFull(reader, got); err != nil || string(got) != string(payload) {
		t.Fatalf("tunnel echo mismatch: %q %v", got, err)
	}
	socks.Close()
	client.Close()
	cancel()
	<-done
}

type launcherProcessLog struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *launcherProcessLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *launcherProcessLog) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

// TestSOARLauncherThroughRealLoopback executes the actual production launcher,
// including its local SOCKS listener and fd-protection handshake. Setting
// SOAR_E2E_CLIENT_BIN to the packaged Android/x86_64 binary additionally checks
// that exact executable on Linux; it is still not an Android VPN claim.
func TestSOARLauncherThroughRealLoopback(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dns, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); dns.Close() })
	go func() {
		_ = soar.Serve(ctx, dns, soar.ServerConfig{
			Zone: "t.example.com", PrivateKey: priv, Dial: (&net.Dialer{}).DialContext,
		})
	}()
	origin, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { origin.Close() })
	go func() {
		for {
			conn, err := origin.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
		}
	}()

	work := t.TempDir()
	binaryPath := os.Getenv("SOAR_E2E_CLIENT_BIN")
	if binaryPath == "" {
		binaryPath = filepath.Join(work, "omni-soar")
		build := exec.Command("go", "build", "-buildvcs=false", "-o", binaryPath, ".")
		build.Env = append(os.Environ(), "CGO_ENABLED=0")
		if output, err := build.CombinedOutput(); err != nil {
			t.Fatalf("build launcher: %v\n%s", err, output)
		}
	} else {
		binaryPath, err = filepath.Abs(binaryPath)
		if err != nil {
			t.Fatal(err)
		}
	}
	resolvers := filepath.Join(work, "resolvers.txt")
	if err := os.WriteFile(resolvers, []byte(dns.LocalAddr().String()+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	settingsPath := filepath.Join(work, "settings.json")
	if err := os.WriteFile(settingsPath, []byte(`{"discovery":false,"transport":"off"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	protectPath := filepath.Join(work, "protect.sock")
	protectListener, err := net.Listen("unix", protectPath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { protectListener.Close() })
	protectSeen := make(chan int, 16)
	go func() {
		for {
			conn, err := protectListener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				payload := make([]byte, 1)
				oob := make([]byte, syscall.CmsgSpace(4))
				_, oobn, _, _, err := conn.(*net.UnixConn).ReadMsgUnix(payload, oob)
				if err != nil {
					return
				}
				messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
				if err != nil {
					return
				}
				count := 0
				for i := range messages {
					fds, err := syscall.ParseUnixRights(&messages[i])
					if err != nil {
						return
					}
					for _, fd := range fds {
						_ = syscall.Close(fd)
					}
					count += len(fds)
				}
				_, _ = conn.Write([]byte{1})
				select {
				case protectSeen <- count:
				default:
				}
			}()
		}
	}()

	ready, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listenAddress := ready.Addr().String()
	_ = ready.Close()
	logs := &launcherProcessLog{}
	cmd := exec.Command(binaryPath,
		"-listen", listenAddress,
		"-domain", "t.example.com",
		"-public-key", base64.RawURLEncoding.EncodeToString(pub),
		"-resolvers", resolvers,
		"-settings", settingsPath,
		"-fd-control-unix-socket", protectPath,
	)
	cmd.Stdout = logs
	cmd.Stderr = logs
	if err := cmd.Start(); err != nil {
		t.Fatalf("start launcher: %v", err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill() })
	waitDone := make(chan error, 1)
	go func() { waitDone <- cmd.Wait() }()

	var socks net.Conn
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		socks, err = net.DialTimeout("tcp", listenAddress, 200*time.Millisecond)
		if err == nil {
			break
		}
		select {
		case waitErr := <-waitDone:
			t.Fatalf("launcher exited before readiness: %v\n%s", waitErr, logs.String())
		default:
		}
		time.Sleep(50 * time.Millisecond)
	}
	if socks == nil {
		t.Fatalf("launcher SOCKS listener was not ready: %v\n%s", err, logs.String())
	}
	_ = socks.SetDeadline(time.Now().Add(30 * time.Second))
	reader := bufio.NewReader(socks)
	if _, err := socks.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(reader, greeting); err != nil || greeting[1] != 0 {
		t.Fatalf("launcher greeting failed: %v %v\n%s", greeting, err, logs.String())
	}
	host, portText, err := net.SplitHostPort(origin.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	request := []byte{5, 1, 0, 1}
	request = append(request, net.ParseIP(host).To4()...)
	request = append(request, byte(port>>8), byte(port))
	if _, err := socks.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil || reply[1] != 0 {
		t.Fatalf("launcher tunnel CONNECT failed: %v %v\n%s", reply, err, logs.String())
	}
	payload := []byte("omni-soar-production-launcher")
	if _, err := socks.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(reader, got); err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("launcher tunnel echo mismatch: %q %v\n%s", got, err, logs.String())
	}
	select {
	case count := <-protectSeen:
		if count != 1 {
			t.Fatalf("fd-protection request carried %d descriptors", count)
		}
	default:
		t.Fatal("real launcher did not request upstream fd protection")
	}
	_ = socks.Close()
	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	select {
	case <-waitDone:
	case <-time.After(10 * time.Second):
		_ = cmd.Process.Kill()
		t.Fatal("launcher did not stop after SIGTERM")
	}
}
