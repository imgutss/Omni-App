// omni-client is the small process boundary between Omni's Android lifecycle
// and the pinned NoizDNS mobile library. It intentionally exposes only the
// options represented by an imported profile; it is not a VPN simulator.
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"noizdns/mobile"
)

// tunnel is implemented by both the ordinary mobile client and the mixed
// resolver composite. The lifecycle and health monitor stay identical.
type tunnel interface {
	Start() error
	Stop()
	IsRunning() bool
}

type clientOptions struct {
	domain, publicKey, listenAddr   string
	utlsDistribution, resolverMode  string
	protectAddress                  string
	authoritative, noiz, stealth    bool
	rawTCP                          bool
	maxPayload, edns0Size, rrSpread int
}

func (o clientOptions) newClient(dnsAddr, listenAddr string, authoritative bool) (*mobile.DnsttClient, error) {
	client, err := mobile.NewClient(dnsAddr, o.domain, o.publicKey, listenAddr)
	if err != nil {
		return nil, err
	}
	if o.utlsDistribution != "" {
		client.SetUTLSFingerprint(o.utlsDistribution)
	}
	client.SetAuthoritativeMode(authoritative)
	client.SetNoizMode(o.noiz)
	if o.stealth {
		client.SetStealthMode(true)
	}
	if o.maxPayload > 0 {
		client.SetMaxPayload(o.maxPayload)
	}
	if o.edns0Size > 0 {
		client.SetEDNS0Size(o.edns0Size)
	}
	client.SetResolverMode(o.resolverMode)
	client.SetRRSpreadCount(int64(o.rrSpread))
	if username := os.Getenv("OMNI_DNSTT_SOCKS_USER"); username != "" {
		client.SetSocksCredentials(username, os.Getenv("OMNI_DNSTT_SOCKS_PASSWORD"))
	}
	return client, nil
}

func main() {
	var dohURL, dotAddr, tcpAddr, udpAddr string
	var publicKey, utlsDistribution, resolverMode, protectAddress string
	var resolverAuthority string
	var authoritative, noiz, stealth, rawTCP bool
	var maxPayload, edns0Size, rrSpread int

	flag.StringVar(&dohURL, "doh", "", "DNS-over-HTTPS resolver URL")
	flag.StringVar(&dotAddr, "dot", "", "DNS-over-TLS resolver(s), comma-separated")
	flag.StringVar(&tcpAddr, "tcp", "", "plain TCP DNS resolver(s), comma-separated")
	flag.StringVar(&udpAddr, "udp", "", "UDP DNS resolver(s), comma-separated")
	flag.StringVar(&publicKey, "pubkey", "", "server Noise public key (64 hex characters)")
	flag.StringVar(&utlsDistribution, "utls", "", "uTLS fingerprint distribution")
	flag.StringVar(&resolverMode, "resolver-mode", "fanout", "fanout or roundrobin")
	flag.StringVar(&protectAddress, "fd-control-unix-socket", "", "Android FD-protection endpoint")
	flag.StringVar(&resolverAuthority, "resolver-authoritative", "", "per-resolver 0/1 modes, comma-separated")
	flag.BoolVar(&authoritative, "authoritative", false, "authoritative-resolver tuning")
	flag.BoolVar(&noiz, "noiz", false, "enable NoizDNS mode")
	flag.BoolVar(&stealth, "stealth", false, "enable NoizDNS stealth mode")
	flag.BoolVar(&rawTCP, "raw-tcp", false, "expose raw TCP for an SSH layer instead of local SOCKS negotiation")
	flag.IntVar(&maxPayload, "max-payload", 0, "DNS payload cap; zero uses the default")
	flag.IntVar(&edns0Size, "edns0-size", 0, "EDNS(0) UDP payload size; zero uses the default")
	flag.IntVar(&rrSpread, "rr-spread", 3, "round-robin resolver spread")
	flag.Parse()

	if flag.NArg() != 2 {
		log.Fatalf("usage: omni-client [options] DOMAIN LOCALADDR:LOCALPORT")
	}
	transports := 0
	for _, value := range []string{dohURL, dotAddr, tcpAddr, udpAddr} {
		if value != "" {
			transports++
		}
	}
	if transports != 1 {
		log.Fatalf("exactly one of -doh, -dot, -tcp, or -udp is required")
	}
	if publicKey == "" {
		log.Fatalf("-pubkey is required")
	}
	if stealth && !noiz {
		log.Fatalf("-stealth requires -noiz")
	}
	if rrSpread < 1 || rrSpread > 5 {
		log.Fatalf("-rr-spread must be between 1 and 5")
	}

	var dnsAddr string
	switch {
	case dohURL != "":
		dnsAddr = dohURL
	case dotAddr != "":
		dnsAddr = prefixEndpoints(dotAddr, "tls://")
	case tcpAddr != "":
		dnsAddr = prefixEndpoints(tcpAddr, "tcp://")
	case udpAddr != "":
		dnsAddr = udpAddr
	}

	options := clientOptions{
		domain: flag.Arg(0), publicKey: publicKey, listenAddr: flag.Arg(1),
		utlsDistribution: utlsDistribution, resolverMode: resolverMode,
		protectAddress: protectAddress, authoritative: authoritative,
		noiz: noiz, stealth: stealth, rawTCP: rawTCP, maxPayload: maxPayload,
		edns0Size: edns0Size, rrSpread: rrSpread,
	}
	authority, err := parseAuthorityFlags(resolverAuthority, resolverCount(dnsAddr))
	if err != nil {
		log.Fatalf("resolver authority: %v", err)
	}
	mixed := hasMixedAuthority(authority)
	if len(authority) > 0 && !mixed {
		// A uniform list is safe for the original single-tunnel API. For a
		// mixed list the per-resolver markers govern each child tunnel below
		// unless the legacy scalar explicitly overrides the whole pool.
		options.authoritative = options.authoritative || authority[0]
	}
	if mixed && dohURL != "" {
		log.Fatalf("mixed per-resolver authority requires UDP, TCP, or DoT resolvers")
	}
	mobile.SetFDControlUnixSocket(protectAddress)

	var process tunnel
	if mixed || rawTCP {
		// The mobile client's own listener always speaks SOCKS5. SSH needs
		// the raw DNSTT byte stream instead, so even a single-resolver profile
		// must use the composite's raw relay rather than silently ignoring
		// -raw-tcp on the ordinary client path.
		if rawTCP && len(authority) == 0 {
			authority = make([]bool, resolverCount(dnsAddr))
		}
		process, err = newMixedTunnel(options, strings.Split(dnsAddr, ","), authority)
	} else {
		process, err = options.newClient(dnsAddr, options.listenAddr, options.authoritative)
	}
	if err != nil {
		log.Fatalf("create client: %v", err)
	}
	if err := process.Start(); err != nil {
		log.Fatalf("start tunnel: %v", err)
	}
	log.Printf("NoizDNS process started (noiz=%t authoritative=%t mixed-resolvers=%t)", noiz, options.authoritative, mixed)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(sigCh)
	health := time.NewTicker(500 * time.Millisecond)
	defer health.Stop()
	for {
		select {
		case <-sigCh:
			done := make(chan struct{})
			go func() {
				process.Stop()
				close(done)
			}()
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				log.Printf("NoizDNS stop timed out; exiting")
			}
			return
		case <-health.C:
			if !process.IsRunning() {
				// Do not leave a seemingly alive Android process after a
				// tunnel goroutine has failed.
				log.Printf("NoizDNS tunnel stopped unexpectedly")
				os.Exit(1)
			}
		}
	}
}

func resolverCount(dnsAddr string) int {
	return len(strings.Split(dnsAddr, ","))
}

func parseAuthorityFlags(raw string, count int) ([]bool, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, nil
	}
	parts := strings.Split(raw, ",")
	if len(parts) != count || count < 1 {
		return nil, fmt.Errorf("got %d flags for %d resolvers", len(parts), count)
	}
	flags := make([]bool, len(parts))
	for i, part := range parts {
		switch strings.TrimSpace(part) {
		case "0":
			flags[i] = false
		case "1":
			flags[i] = true
		default:
			return nil, fmt.Errorf("flag %d must be 0 or 1", i)
		}
	}
	return flags, nil
}

func hasMixedAuthority(flags []bool) bool {
	if len(flags) < 2 {
		return false
	}
	for _, flag := range flags[1:] {
		if flag != flags[0] {
			return true
		}
	}
	return false
}

func prefixEndpoints(raw, prefix string) string {
	parts := strings.Split(raw, ",")
	for i, part := range parts {
		part = strings.TrimSpace(part)
		if !strings.HasPrefix(part, prefix) {
			part = prefix + part
		}
		parts[i] = part
	}
	return strings.Join(parts, ",")
}
