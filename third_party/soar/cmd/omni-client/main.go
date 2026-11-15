// Omni's Soar launcher. It exposes the Apache-2.0 Soar stream client as a local
// SOCKS5 service and adds the Android socket-protection hooks needed by a VPN.
package main

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/getlantern/soar"
	"github.com/getlantern/soar/internal/sockprotect"
)

type settings struct {
	Country              string `json:"country"`
	Transport            string `json:"transport"`
	Discovery            bool   `json:"discovery"`
	DiscoveryWant        int    `json:"discoveryWant"`
	DiscoveryConcurrency int    `json:"discoveryConcurrency"`
	DiscoveryPaceMs      int    `json:"discoveryPaceMs"`
	SafeQName            int    `json:"safeQName"`
	MaxQName             int    `json:"maxQName"`
	MaxAnswer            int    `json:"maxAnswer"`
	HoldMs               int    `json:"holdMs"`
	MaxInflight          int    `json:"maxInflight"`
	MaxPathWindow        int    `json:"maxPathWindow"`
	MaxStreams           int    `json:"maxStreams"`
	StreamWindowKiB      int    `json:"streamWindowKiB"`
	SendBufferKiB        int    `json:"sendBufferKiB"`
	SessionIdleSeconds   int    `json:"sessionIdleSeconds"`
	DialTimeoutSeconds   int    `json:"dialTimeoutSeconds"`
}

func defaultSettings() settings {
	return settings{
		Country: "IR", Transport: "auto", Discovery: true, DiscoveryWant: 2,
		DiscoveryConcurrency: 2, DiscoveryPaceMs: 1000,
		SafeQName: 101, MaxQName: 253, MaxAnswer: 1232, HoldMs: 500,
		MaxInflight: 128, MaxPathWindow: 32, MaxStreams: 128,
		StreamWindowKiB: 256, SendBufferKiB: 64,
		SessionIdleSeconds: 90, DialTimeoutSeconds: 90,
	}
}

func readSettings(path string) (settings, error) {
	s := defaultSettings()
	if strings.TrimSpace(path) == "" {
		return s, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return s, err
	}
	if len(raw) > 64*1024 {
		return s, errors.New("Soar settings are too large")
	}
	if err := json.Unmarshal(raw, &s); err != nil {
		return s, fmt.Errorf("decode Soar settings: %w", err)
	}
	s.Country = strings.ToUpper(strings.TrimSpace(s.Country))
	if err := s.validate(); err != nil {
		return s, err
	}
	return s, nil
}

func (s settings) validate() error {
	if country := strings.ToUpper(strings.TrimSpace(s.Country)); country != "" && country != "IR" {
		return errors.New("country must be IR or empty for global discovery")
	}
	switch s.Transport {
	case "auto", "on", "only", "off":
	default:
		return fmt.Errorf("transport must be auto, on, only or off")
	}
	for _, b := range []struct {
		name             string
		value, low, high int
	}{
		{"discoveryWant", s.DiscoveryWant, 1, 32},
		{"discoveryConcurrency", s.DiscoveryConcurrency, 1, 16},
		{"discoveryPaceMs", s.DiscoveryPaceMs, 100, 5000},
		{"safeQName", s.SafeQName, 101, 253}, {"maxQName", s.MaxQName, s.SafeQName, 253},
		{"maxAnswer", s.MaxAnswer, 512, 1232}, {"holdMs", s.HoldMs, 100, 1500},
		{"maxInflight", s.MaxInflight, 8, 1024}, {"maxPathWindow", s.MaxPathWindow, 2, 128},
		{"maxStreams", s.MaxStreams, 8, 128}, {"streamWindowKiB", s.StreamWindowKiB, 64, 1024},
		{"sendBufferKiB", s.SendBufferKiB, 32, 1024}, {"sessionIdleSeconds", s.SessionIdleSeconds, 15, 110},
		{"dialTimeoutSeconds", s.DialTimeoutSeconds, 5, 120},
	} {
		if b.value < b.low || b.value > b.high {
			return fmt.Errorf("%s must be between %d and %d", b.name, b.low, b.high)
		}
	}
	return nil
}

func decodePublicKey(value string) (ed25519.PublicKey, error) {
	value = strings.TrimSpace(value)
	if raw, err := hex.DecodeString(value); err == nil && len(raw) == ed25519.PublicKeySize {
		return ed25519.PublicKey(raw), nil
	}
	for _, encoding := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding} {
		if raw, err := encoding.DecodeString(value); err == nil && len(raw) == ed25519.PublicKeySize {
			return ed25519.PublicKey(raw), nil
		}
	}
	return nil, errors.New("Soar public key must decode to 32 bytes")
}

func readResolvers(path string) ([]string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(raw) > 4*1024*1024 {
		return nil, errors.New("resolver file is too large")
	}
	seen := make(map[string]bool)
	var out []string
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.ContainsAny(line, "\x00\r") {
			return nil, errors.New("invalid resolver entry")
		}
		// Panel/profile links carry bare IP addresses, while Soar's Config requires
		// host:port. Normalize both forms here instead of silently discarding every
		// configured resolver in addPathLocked.
		if addr, err := netip.ParseAddr(line); err == nil {
			line = netip.AddrPortFrom(addr, 53).String()
		} else if endpoint, err := netip.ParseAddrPort(line); err == nil && endpoint.Port() != 0 {
			line = endpoint.String()
		} else {
			return nil, errors.New("resolver must be an IP address or IP:port")
		}
		if !seen[line] {
			seen[line] = true
			out = append(out, line)
		}
	}
	return out, nil
}

func main() {
	listen := flag.String("listen", "127.0.0.1:18001", "local SOCKS5 listen address")
	domain := flag.String("domain", "", "delegated Soar zone")
	publicKey := flag.String("public-key", "", "32-byte Ed25519 server public key")
	resolversPath := flag.String("resolvers", "", "resolver file")
	settingsPath := flag.String("settings", "", "profile settings JSON")
	protectPath := flag.String("fd-control-unix-socket", "", "Android VPN fd-protection socket")
	flag.Parse()

	if *domain == "" || *publicKey == "" || *resolversPath == "" {
		fmt.Fprintln(os.Stderr, "domain, public-key and resolvers are required")
		os.Exit(2)
	}
	key, err := decodePublicKey(*publicKey)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	resolvers, err := readResolvers(*resolversPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	tuning, err := readSettings(*settingsPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	control := sockprotect.Control(*protectPath)
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	client, err := soar.NewClient(soar.Config{
		Zone: *domain, ServerKey: key, Resolvers: resolvers,
		Discovery: soar.Discovery{
			Enabled: tuning.Discovery, Country: tuning.Country, Want: tuning.DiscoveryWant,
			Concurrency: tuning.DiscoveryConcurrency, Pace: time.Duration(tuning.DiscoveryPaceMs) * time.Millisecond,
		},
		SafeQName: tuning.SafeQName, MaxQName: tuning.MaxQName,
		Hold:        time.Duration(tuning.HoldMs) * time.Millisecond,
		MaxInflight: tuning.MaxInflight, MaxPathWindow: tuning.MaxPathWindow,
		MaxAnswer: tuning.MaxAnswer, StreamWindow: uint64(tuning.StreamWindowKiB) << 10,
		SendBuffer: tuning.SendBufferKiB << 10, MaxStreams: tuning.MaxStreams,
		SessionIdle:  time.Duration(tuning.SessionIdleSeconds) * time.Second,
		TCP:          tuning.Transport,
		ListenPacket: (&net.ListenConfig{Control: control}).ListenPacket,
		DialTCP:      (&net.Dialer{Timeout: 5 * time.Second, Control: control}).DialContext,
		Logger:       logger,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "Soar startup failed: %v\n", err)
		os.Exit(1)
	}
	defer client.Close()
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		fmt.Fprintf(os.Stderr, "SOCKS listen failed: %v\n", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var wg sync.WaitGroup
	go func() {
		<-ctx.Done()
		listener.Close()
		client.Close()
	}()
	logger.Info("Omni Soar local SOCKS listener started", "address", listener.Addr())
	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() == nil {
				logger.Error("SOCKS accept failed", "error", err)
			}
			break
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			serveSOCKS(ctx, conn, client, time.Duration(tuning.DialTimeoutSeconds)*time.Second)
		}()
	}
	wg.Wait()
}
