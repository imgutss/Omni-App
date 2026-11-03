// Package gip finds Google frontend addresses a restricted network still lets a phone reach, and
// tells whether each one carries a fronted request.
//
// A network that allows only Google answers a few of the published frontend addresses and drops
// the rest, so which address works is a property of the network at that moment, not of the relay.
// The scan is therefore cheap and repeatable: TCP + TLS handshake with a Google SNI, then one
// fronted request whose Host is the door's own host.
package gip

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sort"
	"sync"
	"time"

	"guts/protect"
)

// Pool is the published frontend range sampled at build time. It is only a starting point: the
// scanner also asks DNS, and a working address from a previous run can be passed in.
var Pool = []string{
	"216.239.38.120",
	"216.239.32.120",
	"216.239.34.120",
	"216.239.36.120",
	"142.250.72.14",
	"142.250.75.238",
	"142.250.185.78",
	"172.217.16.142",
	"172.217.19.14",
	"173.194.73.14",
	"74.125.130.147",
	"108.177.14.120",
	"64.233.185.120",
	"209.85.202.120",
}

// Result is what one address did.
type Result struct {
	IP      string `json:"ip"`
	RTTMs   int64  `json:"rtt_ms"`            // TCP + TLS handshake
	Fronted bool   `json:"fronted,omitempty"` // the edge carried a request whose Host was FrontHost
	Status  int    `json:"status,omitempty"`
	Err     string `json:"e,omitempty"`
}

// Options controls a scan.
type Options struct {
	SNI         string        // certificate name dialled for, e.g. www.google.com
	FrontHost   string        // Host header of the fronted request, e.g. script.google.com
	Timeout     time.Duration // per address
	Concurrency int
	Extra       []string       // addresses to try besides Pool and DNS
	ResolveHost string         // hostname whose addresses join the pool, e.g. www.google.com
	RootCAs     *x509.CertPool // nil means the system pool
	Insecure    bool           // tests only
}

func (o *Options) withDefaults() {
	if o.SNI == "" {
		o.SNI = "www.google.com"
	}
	if o.FrontHost == "" {
		o.FrontHost = "script.google.com"
	}
	if o.Timeout <= 0 {
		o.Timeout = 4 * time.Second
	}
	if o.Concurrency <= 0 {
		o.Concurrency = 12
	}
}

// Candidates is the address list a scan tries: the built-in pool, whatever DNS answers for
// ResolveHost, and Extra, without duplicates.
func Candidates(ctx context.Context, opts Options) []string {
	opts.withDefaults()
	seen := map[string]struct{}{}
	var out []string
	add := func(ip string) {
		if ip == "" {
			return
		}
		if _, ok := seen[ip]; ok {
			return
		}
		seen[ip] = struct{}{}
		out = append(out, ip)
	}
	for _, ip := range opts.Extra {
		add(ip)
	}
	for _, ip := range Pool {
		add(ip)
	}
	if opts.ResolveHost != "" {
		lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		defer cancel()
		if ips, err := net.DefaultResolver.LookupHost(lookupCtx, opts.ResolveHost); err == nil {
			for _, ip := range ips {
				if net.ParseIP(ip) != nil && net.ParseIP(ip).To4() != nil {
					add(ip)
				}
			}
		}
	}
	return out
}

// Scan probes every candidate and returns the results with the working ones first, fastest first.
func Scan(ctx context.Context, opts Options) []Result {
	opts.withDefaults()
	cands := Candidates(ctx, opts)

	results := make([]Result, 0, len(cands))
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, opts.Concurrency)
	for _, ip := range cands {
		wg.Add(1)
		go func(ip string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			res := Probe(ctx, ip, opts)
			mu.Lock()
			results = append(results, res)
			mu.Unlock()
		}(ip)
	}
	wg.Wait()

	sort.SliceStable(results, func(i, j int) bool {
		a, b := results[i], results[j]
		if a.Err == "" && b.Err != "" {
			return true
		}
		if a.Err != "" && b.Err == "" {
			return false
		}
		if a.Fronted != b.Fronted {
			return a.Fronted
		}
		return a.RTTMs < b.RTTMs
	})
	return results
}

// Probe checks one address: a TCP + TLS handshake with the Google SNI, then a fronted request.
func Probe(ctx context.Context, ip string, opts Options) Result {
	opts.withDefaults()
	addr := ip
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(ip, "443")
	}
	res := Result{IP: ip}

	reqCtx, cancel := context.WithTimeout(ctx, opts.Timeout)
	defer cancel()

	tlsCfg := &tls.Config{
		ServerName:         opts.SNI,
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: opts.Insecure,
		RootCAs:            opts.RootCAs,
	}
	started := time.Now()
	rawConn, err := protect.Dial(reqCtx, "tcp", addr)
	if err != nil {
		res.Err = trimErr(err)
		return res
	}
	conn := tls.Client(rawConn, tlsCfg)
	if err := conn.HandshakeContext(reqCtx); err != nil {
		_ = rawConn.Close()
		res.Err = trimErr(err)
		return res
	}
	res.RTTMs = time.Since(started).Milliseconds()

	// The request that matters is the one the door needs: Host of the door over a connection whose
	// certificate name is Google. An edge that answers 421 does not front, and is useless here.
	_ = conn.SetDeadline(time.Now().Add(opts.Timeout))
	req := fmt.Sprintf("GET / HTTP/1.1\r\nHost: %s\r\nUser-Agent: guts-scan/0.1\r\nConnection: close\r\n\r\n", opts.FrontHost)
	if _, werr := conn.Write([]byte(req)); werr != nil {
		res.Err = trimErr(werr)
		_ = conn.Close()
		return res
	}
	status := readStatus(conn)
	_ = conn.Close()
	res.Status = status
	switch {
	case status == 0:
		res.Err = "no answer to the fronted request"
	case status == 421:
		res.Err = "edge refuses fronting (421)"
	case status >= 500:
		res.Err = fmt.Sprintf("edge error %d", status)
	default:
		res.Fronted = true
	}
	return res
}

// readStatus reads the status code of an HTTP/1 response without buffering its body.
func readStatus(conn net.Conn) int {
	buf := make([]byte, 32)
	n, err := conn.Read(buf)
	if err != nil || n < 12 {
		return 0
	}
	// "HTTP/1.1 404 Not Found"
	line := string(buf[:n])
	if len(line) < 12 || line[:4] != "HTTP" {
		return 0
	}
	code := 0
	for i := 9; i < 12 && i < len(line); i++ {
		c := line[i]
		if c < '0' || c > '9' {
			return 0
		}
		code = code*10 + int(c-'0')
	}
	return code
}

// Best is the fastest address that carried a fronted request, or empty.
func Best(results []Result) string {
	for _, r := range results {
		if r.Err == "" && r.Fronted {
			return r.IP
		}
	}
	return ""
}

// Host returns the best address of a scan, or the first working one, or "".
func Host(ip string) (string, error) {
	if net.ParseIP(ip) == nil {
		return "", errors.New("guts: not an address: " + ip)
	}
	return ip, nil
}

func trimErr(err error) string {
	s := err.Error()
	if len(s) > 90 {
		s = s[:90]
	}
	return s
}

// Client is a tiny helper for callers that only want to know whether fronting works now.
func Client(ip, sni, frontHost string, timeout time.Duration) (*http.Client, error) {
	if ip == "" {
		return nil, errors.New("guts: no edge address")
	}
	addr := net.JoinHostPort(ip, "443")
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			ForceAttemptHTTP2: false,
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				return protect.Dial(ctx, network, addr)
			},
			TLSClientConfig: &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12},
		},
	}, nil
}
