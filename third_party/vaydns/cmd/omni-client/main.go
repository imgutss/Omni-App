// omni-client embeds the CC0 VayDNS core in an Android-friendly process.
// It adds resolver pooling, TCP DNS, socket protection, and optional local
// SOCKS5 authentication injection without depending on vaydns-mobile.
package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	vayclient "github.com/net2share/vaydns/client"
	"github.com/net2share/vaydns/turbotunnel"
	utls "github.com/refraction-networking/utls"
)

type options struct {
	transport    string
	resolvers    string
	domain       string
	publicKey    string
	listen       string
	socksListen  string
	utls         string
	recordType   string
	resolverMode string
	fdControl    string
	compat       bool
	rawTCP       bool
	clientIDSize int
	maxQnameLen  int
	maxPayload   int
	maxNumLabels int
	rps          float64
	idleTimeout  int
	keepAlive    int
	udpTimeout   int
	rrSpread     int
	udpWorkers   int
}

func main() {
	var o options
	var doh, dot, tcp, udp string
	flag.StringVar(&doh, "doh", "", "DoH URL")
	flag.StringVar(&dot, "dot", "", "comma-separated DoT endpoints")
	flag.StringVar(&tcp, "tcp", "", "comma-separated DNS-over-TCP endpoints")
	flag.StringVar(&udp, "udp", "", "comma-separated UDP endpoints")
	flag.StringVar(&o.domain, "domain", "", "tunnel domain")
	flag.StringVar(&o.publicKey, "pubkey", "", "server Noise public key")
	flag.StringVar(&o.listen, "listen", "", "raw or public loopback listener")
	flag.StringVar(&o.socksListen, "socks-listen", "", "public listener when raw listen is used behind SOCKS auth injection")
	flag.StringVar(&o.utls, "utls", "4*random,3*Firefox_120,1*Firefox_105,3*Chrome_120,1*Chrome_102,1*iOS_14,1*iOS_13", "uTLS distribution or none")
	flag.StringVar(&o.recordType, "record-type", "txt", "downstream DNS record type")
	flag.StringVar(&o.resolverMode, "resolver-mode", "fanout", "fanout or roundrobin")
	flag.StringVar(&o.fdControl, "fd-control-unix-socket", "", "Android FD-control socket")
	flag.BoolVar(&o.compat, "dnstt-compat", false, "use DNSTT wire compatibility")
	flag.BoolVar(&o.rawTCP, "raw-tcp", false, "raw stream for an SSH layer; disables the local SOCKS bridge")
	flag.IntVar(&o.clientIDSize, "clientid-size", 0, "VayDNS ClientID bytes; zero uses the default")
	flag.IntVar(&o.maxQnameLen, "max-qname-len", 0, "maximum QNAME wire length")
	flag.IntVar(&o.maxPayload, "max-payload", 0, "cap KCP payload MTU; zero uses full capacity")
	flag.IntVar(&o.maxNumLabels, "max-num-labels", 0, "maximum data labels; zero is unlimited")
	flag.Float64Var(&o.rps, "rps", 0, "DNS queries per second; zero is unlimited")
	flag.IntVar(&o.idleTimeout, "idle-timeout", 0, "smux idle timeout in seconds")
	flag.IntVar(&o.keepAlive, "keep-alive", 0, "smux keepalive interval in seconds")
	flag.IntVar(&o.udpTimeout, "udp-timeout", 500, "per-query UDP timeout in milliseconds")
	flag.IntVar(&o.rrSpread, "rr-spread", 3, "round-robin targets per query")
	flag.IntVar(&o.udpWorkers, "udp-workers", 16, "UDP workers per resolver")
	flag.Parse()

	selected := 0
	for _, candidate := range []struct{ kind, value string }{{"doh", doh}, {"dot", dot}, {"tcp", tcp}, {"udp", udp}} {
		if candidate.value != "" {
			selected++
			o.transport, o.resolvers = candidate.kind, candidate.value
		}
	}
	if selected != 1 || o.domain == "" || o.publicKey == "" || o.listen == "" {
		log.Fatal("exactly one resolver transport, domain, pubkey, and listen are required")
	}
	if o.resolverMode != "fanout" && o.resolverMode != "roundrobin" {
		log.Fatal("invalid resolver mode")
	}
	if o.rrSpread < 1 || o.udpWorkers < 1 || o.udpTimeout < 1 {
		log.Fatal("invalid resolver tuning")
	}

	var utlsID *utls.ClientHelloID
	var err error
	if o.utls != "none" {
		utlsID, err = vayclient.SampleUTLSDistribution(o.utls)
		if err != nil {
			log.Fatalf("uTLS: %v", err)
		}
	}

	factory := &transportFactory{options: o, utlsID: utlsID, control: socketControl(o.fdControl)}
	resolver := vayclient.Resolver{
		ResolverType: vayclient.ResolverTypeUDP,
		PacketConnFactory: func() (net.PacketConn, net.Addr, error) {
			return factory.build()
		},
	}
	server, err := vayclient.NewTunnelServer(o.domain, o.publicKey)
	if err != nil {
		log.Fatal(err)
	}
	server.DnsttCompat = o.compat
	server.ClientIDSize = o.clientIDSize
	server.MaxQnameLen = o.maxQnameLen
	server.MaxNumLabels = o.maxNumLabels
	server.RPS = o.rps
	server.RecordType = o.recordType
	if o.compat {
		// DNSTT's wire format carries downstream data in TXT answers. Match the
		// upstream CLI even if a generic profile also contains another RR type.
		server.RecordType = "txt"
	}

	tunnel, err := vayclient.NewTunnel(resolver, server)
	if err != nil {
		log.Fatal(err)
	}
	tunnel.MaxPayload = o.maxPayload
	if o.idleTimeout > 0 {
		tunnel.IdleTimeout = time.Duration(o.idleTimeout) * time.Second
	}
	if o.keepAlive > 0 {
		tunnel.KeepAlive = time.Duration(o.keepAlive) * time.Second
	}

	if o.rawTCP {
		if err := tunnel.ListenAndServe(o.listen); err != nil {
			log.Fatal(err)
		}
		return
	}

	// VayDNS owns a raw stream listener, while Xray and the readiness check
	// consume SOCKS5. Standalone mode always needs the local bridge, including
	// when the remote service uses no authentication.
	if o.socksListen == "" || o.socksListen == o.listen {
		log.Fatal("standalone SOCKS mode needs distinct raw and public listeners")
	}
	username := os.Getenv("OMNI_DNSTT_SOCKS_USER")
	password := os.Getenv("OMNI_DNSTT_SOCKS_PASSWORD")
	if len(username) > 255 || len(password) > 255 {
		log.Fatal("SOCKS credentials exceed the protocol limit")
	}
	go func() {
		if err := tunnel.ListenAndServe(o.listen); err != nil {
			log.Fatalf("raw VayDNS listener: %v", err)
		}
	}()
	if err := serveSocksAuthBridge(o.socksListen, o.listen, username, password); err != nil {
		log.Fatal(err)
	}
}

type transportFactory struct {
	options options
	utlsID  *utls.ClientHelloID
	control socketControlFunc
}

func (f *transportFactory) build() (net.PacketConn, net.Addr, error) {
	parts := strings.Split(f.options.resolvers, ",")
	children := make([]net.PacketConn, 0, len(parts))
	targets := make([]net.Addr, 0, len(parts))
	closeAll := func() {
		for _, child := range children {
			_ = child.Close()
		}
	}
	for _, part := range parts {
		address := strings.TrimSpace(part)
		if address == "" {
			closeAll()
			return nil, nil, fmt.Errorf("empty resolver")
		}
		child, target, err := f.buildOne(address)
		if err != nil {
			closeAll()
			return nil, nil, err
		}
		children = append(children, child)
		targets = append(targets, target)
	}
	if len(children) == 0 {
		return nil, nil, fmt.Errorf("no resolvers")
	}
	pool, err := newResolverPoolWithTargets(children, targets, f.options.resolverMode, f.options.rrSpread)
	if err != nil {
		closeAll()
		return nil, nil, err
	}
	return pool, turbotunnel.DummyAddr{}, nil
}

func (f *transportFactory) buildOne(address string) (net.PacketConn, net.Addr, error) {
	o := f.options
	switch o.transport {
	case "udp":
		remote, err := net.ResolveUDPAddr("udp", address)
		if err != nil {
			return nil, nil, err
		}
		conn, _, err := vayclient.NewUDPPacketConn(remote, f.control, o.udpWorkers, time.Duration(o.udpTimeout)*time.Millisecond, true, turbotunnel.QueueSize, turbotunnel.QueueOverflowDrop)
		return conn, remote, err
	case "tcp":
		conn, err := vayclient.NewTLSPacketConn(address, f.dialContext(), turbotunnel.QueueSize, turbotunnel.QueueOverflowDrop)
		return conn, turbotunnel.DummyAddr{}, err
	case "dot":
		var dial func(context.Context, string, string) (net.Conn, error)
		if f.utlsID == nil {
			dialer := &tls.Dialer{NetDialer: &net.Dialer{Control: f.control}}
			dial = dialer.DialContext
		} else {
			dial = func(ctx context.Context, network, addr string) (net.Conn, error) {
				return vayclient.UTLSDialContextWithDialer(ctx, network, addr, nil, f.utlsID, f.dialContext())
			}
		}
		conn, err := vayclient.NewTLSPacketConn(address, dial, turbotunnel.QueueSize, turbotunnel.QueueOverflowDrop)
		return conn, turbotunnel.DummyAddr{}, err
	case "doh":
		var rt http.RoundTripper
		if f.utlsID == nil {
			transport := http.DefaultTransport.(*http.Transport).Clone()
			transport.DialContext = f.dialContext()
			rt = transport
		} else {
			rt = vayclient.NewUTLSRoundTripperWithDialer(nil, f.utlsID, f.dialContext())
		}
		conn, err := vayclient.NewHTTPPacketConn(rt, address, 8, turbotunnel.QueueSize, turbotunnel.QueueOverflowDrop)
		return conn, turbotunnel.DummyAddr{}, err
	default:
		return nil, nil, fmt.Errorf("unsupported transport %q", o.transport)
	}
}

func (f *transportFactory) dialContext() func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Control: f.control, Timeout: 30 * time.Second}
	return dialer.DialContext
}
