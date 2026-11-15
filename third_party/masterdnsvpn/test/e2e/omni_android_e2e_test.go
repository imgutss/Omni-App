//go:build e2e

// End-to-end smoke tests for Omni's minimal MasterDNS JSON/TOML profiles and
// exact Android client arguments. All DNS and upstream traffic stays on loopback.
package e2e

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

func TestOmniNativeProfilesThroughAndroidClient(t *testing.T) {
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	clientBin := os.Getenv("MASTERDNS_E2E_CLIENT_BIN")
	if clientBin == "" {
		clientBin = filepath.Join(t.TempDir(), "client")
		buildMasterBinary(t, root, "./cmd/client", clientBin)
	} else if clientBin, err = filepath.Abs(clientBin); err != nil {
		t.Fatal(err)
	}
	for _, format := range []string{"json", "toml"} {
		t.Run(format, func(t *testing.T) {
			testOmniNativeProfile(t, root, clientBin, format)
		})
	}
}

func testOmniNativeProfile(t *testing.T, root, clientBin, format string) {
	work := t.TempDir()
	serverBin := filepath.Join(work, "server")
	buildMasterBinary(t, root, "./cmd/server", serverBin)

	upstreamPort := startMasterUpstream(t)
	dnsPort := freeMasterUDPPort(t)
	clientPort := freeMasterTCPPort(t)
	serverConfig := filepath.Join(work, "server_config.toml")
	serverText := fmt.Sprintf(`PROTOCOL_TYPE = "SOCKS5"
USE_EXTERNAL_SOCKS5 = true
UDP_HOST = "127.0.0.1"
UDP_PORT = %d
DOMAIN = ["a.io"]
MIN_VPN_LABEL_LENGTH = 1
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY_FILE = "encrypt_key.txt"
FORWARD_IP = "127.0.0.1"
FORWARD_PORT = %d
LOG_LEVEL = "INFO"
`, dnsPort, upstreamPort)
	if err := os.WriteFile(serverConfig, []byte(serverText), 0o600); err != nil {
		t.Fatal(err)
	}
	serverLog := &masterLogBuffer{}
	serverCmd := exec.Command(serverBin, "--config", serverConfig)
	serverCmd.Dir = work
	serverCmd.Stdout = serverLog
	serverCmd.Stderr = serverLog
	if err := serverCmd.Start(); err != nil {
		t.Fatal(err)
	}
	serverDone := make(chan error, 1)
	go func() { serverDone <- serverCmd.Wait() }()
	t.Cleanup(func() {
		_ = serverCmd.Process.Kill()
		select {
		case <-serverDone:
		case <-time.After(5 * time.Second):
		}
	})
	keyPath := filepath.Join(work, "encrypt_key.txt")
	key, err := waitMasterKey(keyPath, 30*time.Second)
	if err != nil {
		t.Fatalf("server key: %v\n%s", err, serverLog.String())
	}

	// These are the same native documents accepted for a masterdns:// profile;
	// command-line overrides below supply only listener/resolver behavior.
	var clientDocument []byte
	if format == "json" {
		clientDocument, err = json.Marshal(map[string]any{
			"DOMAINS": []string{"a.io"}, "DATA_ENCRYPTION_METHOD": 1, "ENCRYPTION_KEY": key,
		})
		if err != nil {
			t.Fatal(err)
		}
	} else {
		encodedKey, keyErr := json.Marshal(key)
		if keyErr != nil {
			t.Fatal(keyErr)
		}
		clientDocument = fmt.Appendf(nil, "DOMAINS = [\"a.io\"]\nDATA_ENCRYPTION_METHOD = 1\nENCRYPTION_KEY = %s\n", encodedKey)
	}
	clientConfig := filepath.Join(work, "client_config."+format)
	if err := os.WriteFile(clientConfig, clientDocument, 0o600); err != nil {
		t.Fatal(err)
	}
	resolvers := filepath.Join(work, "client_resolvers.txt")
	if err := os.WriteFile(resolvers, []byte(net.JoinHostPort("127.0.0.1", strconv.Itoa(dnsPort))+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	clientLog := &masterLogBuffer{}
	clientCmd := exec.Command(clientBin,
		"-config="+clientConfig,
		"-resolvers="+resolvers,
		"-protocol-type=SOCKS5",
		"-listen-ip=127.0.0.1",
		fmt.Sprintf("-listen-port=%d", clientPort),
		"-socks5-auth=false",
		"-local-dns-enabled=false",
		"-local-dns-cache-persist-to-file=false",
		"-save-mtu-servers-to-file=false",
		"-fd-control-unix-socket=",
	)
	clientCmd.Dir = work
	clientCmd.Stdout = clientLog
	clientCmd.Stderr = clientLog
	if err := clientCmd.Start(); err != nil {
		t.Fatal(err)
	}
	clientDone := make(chan error, 1)
	go func() { clientDone <- clientCmd.Wait() }()
	t.Cleanup(func() {
		_ = clientCmd.Process.Kill()
		select {
		case <-clientDone:
		case <-time.After(5 * time.Second):
		}
	})
	if err := waitMasterSocks(clientPort, clientDone, 150*time.Second); err != nil {
		t.Fatalf("client readiness: %v\n--- client ---\n%s\n--- server ---\n%s", err, clientLog.String(), serverLog.String())
	}

	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(clientPort)), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(120 * time.Second))
	if err := masterSocksConnect(conn); err != nil {
		t.Fatalf("remote CONNECT: %v\n--- client ---\n%s", err, clientLog.String())
	}
	payload := bytes.Repeat([]byte("omni-masterdns-loopback-"), 1024)
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("tunneled payload did not round trip byte for byte")
	}
}

func buildMasterBinary(t *testing.T, root, pkg, output string) {
	t.Helper()
	cmd := exec.Command("go", "build", "-buildvcs=false", "-o", output, pkg)
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "CGO_ENABLED=0")
	if raw, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", pkg, err, raw)
	}
}

func freeMasterTCPPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

func freeMasterUDPPort(t *testing.T) int {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).Port
}

type masterLogBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (b *masterLogBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.Write(p)
}

func (b *masterLogBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.String()
}

func waitMasterKey(path string, timeout time.Duration) (string, error) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		raw, err := os.ReadFile(path)
		if err == nil && len(bytes.TrimSpace(raw)) > 0 {
			return string(bytes.TrimSpace(raw)), nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return "", fmt.Errorf("timed out waiting for generated key")
}

func waitMasterSocks(port int, exited <-chan error, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 300*time.Millisecond)
		if err == nil {
			_ = conn.SetDeadline(time.Now().Add(time.Second))
			_, _ = conn.Write([]byte{5, 1, 0})
			reply := make([]byte, 2)
			_, readErr := io.ReadFull(conn, reply)
			_ = conn.Close()
			if readErr == nil && reply[0] == 5 && reply[1] == 0 {
				return nil
			}
		}
		select {
		case err := <-exited:
			return fmt.Errorf("client exited: %v", err)
		default:
		}
		time.Sleep(200 * time.Millisecond)
	}
	return fmt.Errorf("SOCKS greeting timed out")
}

func masterSocksConnect(conn net.Conn) error {
	if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
		return err
	}
	reader := bufio.NewReader(conn)
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(reader, greeting); err != nil || greeting[1] != 0 {
		return fmt.Errorf("greeting %v: %w", greeting, err)
	}
	// Public documentation address: the server's external-upstream fixture
	// echoes it without making an Internet connection or violating loopback rules.
	request := []byte{5, 1, 0, 1, 192, 0, 2, 10, 0, 80}
	if _, err := conn.Write(request); err != nil {
		return err
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil {
		return err
	}
	if reply[0] != 5 || reply[1] != 0 {
		return fmt.Errorf("CONNECT rejected with status %d", reply[1])
	}
	return nil
}

// startMasterUpstream is an external SOCKS fixture. It accepts the public test
// target itself, then echoes the byte stream; no public-network dial occurs.
func startMasterUpstream(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				reader := bufio.NewReader(conn)
				header := make([]byte, 2)
				if _, err := io.ReadFull(reader, header); err != nil || header[0] != 5 {
					return
				}
				if _, err := io.CopyN(io.Discard, reader, int64(header[1])); err != nil {
					return
				}
				if _, err := conn.Write([]byte{5, 0}); err != nil {
					return
				}
				request := make([]byte, 4)
				if _, err := io.ReadFull(reader, request); err != nil {
					return
				}
				var hostBytes int64
				switch request[3] {
				case 1:
					hostBytes = 4
				case 4:
					hostBytes = 16
				case 3:
					length, err := reader.ReadByte()
					if err != nil {
						return
					}
					hostBytes = int64(length)
				default:
					return
				}
				if _, err := io.CopyN(io.Discard, reader, hostBytes+2); err != nil {
					return
				}
				if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
					return
				}
				_, _ = io.Copy(conn, reader)
			}()
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port
}
