// Package gip finds Google frontend addresses a restricted network still lets a phone reach, and
// tells whether each one carries a fronted request.
//
// A network that allows only Google answers a few of the published frontend addresses and drops
// the rest, and filters some of the names a ClientHello may carry while letting others through.
// Both the address and the name are therefore properties of the network — more precisely of the
// operator line — at that moment, not of the relay. The scanner treats them as one question and
// answers it the way the tunnel itself would: a TCP connection, a TLS handshake with a Google
// SNI, then one fronted request whose Host is the door's own host.
//
// Three details decide whether a scan tells the truth on a slow line, and all three used to be
// wrong here:
//
//   - Time is budgeted per stage, not per probe. A line whose handshake alone takes three
//     seconds (a measured value on a real operator) is slow, not dead; one shared deadline for
//     connect and handshake calls it dead.
//   - The address and the name are scanned as a matrix, not in sequence. Trying the alternative
//     names only after one address has answered under www.google.com never reaches the names on
//     a network where www itself is the filtered one.
//   - The candidate addresses come from the line itself first: whatever the operator's resolver
//     answers for Google's hosts is the frontend pool that operator routes (and, in a national
//     network, whitelists), which differs per SIM in a way no built-in list can know. On Android
//     the app resolves those names with the platform resolver and hands the addresses in as
//     Options.Extra, because a pure-Go resolver has no /etc/resolv.conf to read there.
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
	"strings"
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

// SniPool is the set of Google host names whose certificates a fronted call may be made under. A
// network that blocks Google by name blocks some of these and not others, so which one works is a
// property of the network, exactly like the address pool above. The static-content and API names
// (gstatic, googleapis, gvt2) are what real apps are already reaching for on those lines, so
// they are often the ones still let through when www itself is filtered or throttled.
var SniPool = []string{
	"www.workspace.google.com",
	"www.mail.google.com",
	"www.gstatic.com",
	"www.google.com",
	"www.gmail.com",
	"workspace.google.com",
	"ssl.gstatic.com",
	"mail.google.com",
	"gstatic.com",
	"google.com",
	"gmail.com",
	"g3.gstatic.com",
	"g1.gstatic.com",
	"fonts.gstatic.com",
	"csi.gstatic.com",
	"connectivitycheck.gstatic.com",
	"clientservices.googleapis.com",
	"checkin.gstatic.com",
	"beacons.gvt2.com",
	"beacons.gcp.gvt2.com",
	"drive.google.com",
	"docs.google.com",
	"accounts.google.com",
	"photos.google.com",
	"calendar.google.com",
	"meet.google.com",
	"myaccount.google.com",
	"maps.google.com",
}

// DefaultResolverHosts are the Google names whose DNS answers seed the candidate addresses. A
// mobile operator's resolver returns the frontend addresses that operator actually routes for
// these names, which is why the same phone finds a different working edge on Irancell than on
// Hamrah Aval, and a different one again on home Wi-Fi.
var DefaultResolverHosts = []string{
	"www.google.com",
	"mail.google.com",
	"drive.google.com",
	"script.google.com",
	"www.gstatic.com",
	"fonts.gstatic.com",
	"connectivitycheck.gstatic.com",
}

// Result is what one address did.
type Result struct {
	IP      string `json:"ip"`
	SNI     string `json:"sni,omitempty"`
	RTTMs   int64  `json:"rtt_ms"`            // TCP + TLS handshake
	Fronted bool   `json:"fronted,omitempty"` // the edge carried a request whose Host was FrontHost
	Status  int    `json:"status,omitempty"`
	Err     string `json:"e,omitempty"`
}

// Options controls a scan.
type Options struct {
	SNI       string // certificate name dialled for, e.g. www.google.com
	FrontHost string // Host header of the fronted request, e.g. script.google.com

	// Timeout is the budget of one stage of a probe — the TCP connect, the TLS handshake, and
	// the fronted read each get it in full, because on a throttled line any one of them can
	// legitimately take most of it.
	Timeout time.Duration

	// QuickTimeout bounds the bare TCP connect that prunes the candidate addresses before the
	// expensive handshakes start.
	QuickTimeout time.Duration

	Concurrency int

	// SNIs, when set, is the exact list of names to try. AllSNIs instead tries SNI first and
	// then the whole SniPool. With neither, only SNI is tried — what a caller that has already
	// pinned a name wants.
	SNIs    []string
	AllSNIs bool

	// MaxEdges caps how many of the fastest TCP-reachable addresses go on to the full
	// address-by-name matrix.
	MaxEdges int

	Extra []string // addresses to try besides Pool and DNS; they are tried first

	// ResolveHost (one name) and ResolveHosts (several) name the hosts whose DNS answers join
	// the pool. Leaving both nil resolves DefaultResolverHosts; an explicitly empty ResolveHosts
	// resolves nothing.
	ResolveHost  string
	ResolveHosts []string

	RootCAs  *x509.CertPool // nil means the system pool
	Only     []string       // when set, these addresses and no others
	Insecure bool           // tests only
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
	if o.QuickTimeout <= 0 {
		o.QuickTimeout = 3 * time.Second
	}
	if o.Concurrency <= 0 {
		o.Concurrency = 12
	}
	if o.MaxEdges <= 0 {
		o.MaxEdges = 6
	}
	if o.ResolveHost == "" && o.ResolveHosts == nil {
		o.ResolveHosts = DefaultResolverHosts
	}
}

// candidateSNIs is the ordered, deduplicated list of names a scan may dial under.
func candidateSNIs(opts Options) []string {
	seen := map[string]struct{}{}
	var out []string
	add := func(name string) {
		name = strings.TrimSpace(name)
		if name == "" {
			return
		}
		if _, ok := seen[name]; ok {
			return
		}
		seen[name] = struct{}{}
		out = append(out, name)
	}
	if len(opts.SNIs) > 0 {
		for _, name := range opts.SNIs {
			add(name)
		}
		return out
	}
	add(opts.SNI)
	if opts.AllSNIs {
		for _, name := range SniPool {
			add(name)
		}
	}
	return out
}

// Candidates is the address list a scan tries: the extras handed in first (on a phone those are
// this line's own DNS answers, resolved by the app), then whatever DNS answers for the resolver
// hosts here, then the built-in pool, without duplicates.
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
	hosts := make([]string, 0, len(opts.ResolveHosts)+1)
	if opts.ResolveHost != "" {
		hosts = append(hosts, opts.ResolveHost)
	}
	hosts = append(hosts, opts.ResolveHosts...)
	for _, ip := range resolveAll(ctx, hosts) {
		add(ip)
	}
	for _, ip := range Pool {
		add(ip)
	}
	return out
}

// resolveAll asks DNS for every host at once and returns the IPv4 answers, best effort. A lookup
// that fails or runs past the shared deadline simply contributes nothing: the built-in pool is
// still there, and on Android this lookup is expected to fail (no resolv.conf for a pure-Go
// binary), which is why the app's own answers travel in as extras.
func resolveAll(ctx context.Context, hosts []string) []string {
	if len(hosts) == 0 {
		return nil
	}
	lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	answers := make([][]string, len(hosts))
	var wg sync.WaitGroup
	for i, host := range hosts {
		wg.Add(1)
		go func(i int, host string) {
			defer wg.Done()
			ips, err := net.DefaultResolver.LookupHost(lookupCtx, host)
			if err != nil {
				return
			}
			for _, ip := range ips {
				if parsed := net.ParseIP(ip); parsed != nil && parsed.To4() != nil {
					answers[i] = append(answers[i], ip)
				}
			}
		}(i, host)
	}
	wg.Wait()
	var out []string
	for _, ips := range answers {
		out = append(out, ips...)
	}
	return out
}

// addressesOf is the candidate list a scan with these options works on.
func addressesOf(ctx context.Context, opts Options) []string {
	if len(opts.Only) > 0 {
		return opts.Only
	}
	return Candidates(ctx, opts)
}

// pair is one cell of the address-by-name matrix.
type pair struct {
	ip   string
	sni  string
	rank int
}

// pairsOf lists the matrix in "wavefront" order — (best address, best name) first, then the
// cells one step away in either dimension — so a racing scan spends its first probes on a spread
// of addresses and names instead of exhausting one name that this line may be filtering.
func pairsOf(addrs, snis []string) []pair {
	out := make([]pair, 0, len(addrs)*len(snis))
	for i, ip := range addrs {
		for j, name := range snis {
			out = append(out, pair{ip: ip, sni: name, rank: (i+j)*1000 + i})
		}
	}
	sort.SliceStable(out, func(a, b int) bool { return out[a].rank < out[b].rank })
	return out
}

// quickResult is the outcome of the bare-TCP pruning pass.
type quickResult struct {
	ip    string
	rttMs int64
	err   string
}

// quickTCP measures a bare TCP connect: enough to drop the addresses this line silently eats
// before spending handshakes on them.
func quickTCP(ctx context.Context, ip string, timeout time.Duration) quickResult {
	addr := dialAddr(ip)
	dialCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	started := time.Now()
	conn, err := protect.Dial(dialCtx, "tcp", addr)
	if err != nil {
		return quickResult{ip: ip, err: trimErr(err)}
	}
	_ = conn.Close()
	return quickResult{ip: ip, rttMs: time.Since(started).Milliseconds()}
}

// fastestReachable runs the pruning pass over addrs and returns the fastest addresses that
// answered a TCP connect, fastest first. Failures are returned too, for the callers whose job is
// to report them.
func fastestReachable(ctx context.Context, addrs []string, opts Options) (reachable []string, failed []Result) {
	results := make([]quickResult, len(addrs))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 24)
	for i, ip := range addrs {
		wg.Add(1)
		go func(i int, ip string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			results[i] = quickTCP(ctx, ip, opts.QuickTimeout)
		}(i, ip)
	}
	wg.Wait()
	sort.SliceStable(results, func(i, j int) bool {
		a, b := results[i], results[j]
		if (a.err == "") != (b.err == "") {
			return a.err == ""
		}
		return a.rttMs < b.rttMs
	})
	for _, res := range results {
		if res.err != "" {
			failed = append(failed, Result{IP: res.ip, Err: res.err})
			continue
		}
		if len(reachable) < opts.MaxEdges {
			reachable = append(reachable, res.ip)
		}
	}
	return reachable, failed
}

// firstFronted races full probes across the address-by-name matrix and returns the first cell
// that carries a fronted request, cancelling the rest. It is the fast answer a connect path
// needs; ScanMatrix is the slow, complete one a scanner screen shows.
func firstFronted(ctx context.Context, addrs, snis []string, opts Options) (Result, bool) {
	if len(addrs) > 2 {
		reachable, failed := fastestReachable(ctx, addrs, opts)
		if len(reachable) == 0 {
			if len(failed) > 0 {
				return failed[0], false
			}
			return Result{Err: "no address answered"}, false
		}
		addrs = reachable
	}
	cells := pairsOf(addrs, snis)
	raceCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	jobs := make(chan pair)
	results := make(chan Result, len(cells))
	workers := opts.Concurrency
	if workers > len(cells) {
		workers = len(cells)
	}
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for cell := range jobs {
				if raceCtx.Err() != nil {
					return
				}
				attempt := opts
				attempt.SNI = cell.sni
				results <- Probe(raceCtx, cell.ip, attempt)
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, cell := range cells {
			select {
			case <-raceCtx.Done():
				return
			case jobs <- cell:
			}
		}
	}()
	go func() {
		wg.Wait()
		close(results)
	}()

	var last Result
	for res := range results {
		if res.Fronted {
			cancel()
			return res, true
		}
		last = res
	}
	if last.IP == "" {
		last.Err = "no address answered"
	}
	return last, false
}

// Reachable answers the one question a phone asks before it tries: does this network let me reach
// the Google edge at all, under any of the names it still answers? It returns as soon as one
// address under one name carries a fronted request, so a healthy network costs one handshake
// and a dead one costs the caller's deadline. Nothing here touches Apps Script, so the answer is
// free of quota.
func Reachable(ctx context.Context, opts Options) (Result, bool) {
	opts.withDefaults()
	return firstFronted(ctx, addressesOf(ctx, opts), candidateSNIs(opts), opts)
}

// ScanSNIs asks one edge, with every name at once, to be reached under each of several Google
// names, and reports them in the order they were asked for. TLS carries the name in the clear in
// its ClientHello, so a network that filters on the name answers for some and drops the others;
// the first name that answers is the one the phone should pin.
func ScanSNIs(ctx context.Context, ip string, snis []string, opts Options) []Result {
	opts.withDefaults()
	if len(snis) == 0 {
		snis = SniPool
	}
	out := make([]Result, len(snis))
	var wg sync.WaitGroup
	sem := make(chan struct{}, opts.Concurrency)
	for i, name := range snis {
		out[i] = Result{IP: ip, SNI: name, Err: "not tried"}
		wg.Add(1)
		go func(i int, name string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if ctx.Err() != nil {
				out[i].Err = trimErr(ctx.Err())
				return
			}
			attempt := opts
			attempt.SNI = name
			res := Probe(ctx, ip, attempt)
			res.SNI = name
			out[i] = res
		}(i, name)
	}
	wg.Wait()
	return out
}

// BestSNI is the fastest name that carried a fronted call, or empty when none did.
func BestSNI(results []Result) string {
	best := ""
	var bestRTT int64
	for _, res := range results {
		if !res.Fronted {
			continue
		}
		if best == "" || res.RTTMs < bestRTT {
			best = res.SNI
			bestRTT = res.RTTMs
		}
	}
	return best
}

// BestPair is the fastest address-and-name cell that carried a fronted request.
func BestPair(results []Result) (Result, bool) {
	var best Result
	found := false
	for _, res := range results {
		if !res.Fronted {
			continue
		}
		if !found || res.RTTMs < best.RTTMs {
			best, found = res, true
		}
	}
	return best, found
}

// Scan probes every candidate under the one configured name and returns the results with the
// working ones first, fastest first. ScanMatrix is the fuller question; this remains the cheap
// one for a caller that has already pinned its name.
func Scan(ctx context.Context, opts Options) []Result {
	opts.withDefaults()
	cands := addressesOf(ctx, opts)

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

	sortResults(results)
	return results
}

// maxFrontChecks bounds the full fronted probes of a matrix scan: every cell gets a handshake,
// but only the fastest handshakes are asked the expensive fronted question.
const maxFrontChecks = 48

// ScanMatrix asks the complete question a scanner screen exists to answer: for this line, which
// (address, name) pairs carry a fronted call, and how fast is each? It prunes the addresses with
// bare TCP connects, handshakes every remaining pair, then spends full fronted probes on the
// fastest handshakes. Every pair that was tried is reported, fastest fronted first, so the top
// row is the pair the profile should pin and the rest is the evidence.
func ScanMatrix(ctx context.Context, opts Options) []Result {
	opts.withDefaults()
	addrs := addressesOf(ctx, opts)
	snis := candidateSNIsWithPool(opts)

	var failed []Result
	if len(addrs) > 1 {
		reachable, dead := fastestReachable(ctx, addrs, opts)
		failed = dead
		addrs = reachable
	}
	if len(addrs) == 0 {
		sortResults(failed)
		return failed
	}

	cells := pairsOf(addrs, snis)
	handshakes := make([]Result, len(cells))
	runCells(ctx, cells, 24, func(cell pair) Result {
		attempt := opts
		attempt.SNI = cell.sni
		return probeHandshake(ctx, cell.ip, attempt)
	}, handshakes)

	// The fronted question goes to the fastest handshakes first.
	order := make([]int, 0, len(cells))
	for i, res := range handshakes {
		if res.Err == "" {
			order = append(order, i)
		}
	}
	sort.SliceStable(order, func(a, b int) bool { return handshakes[order[a]].RTTMs < handshakes[order[b]].RTTMs })
	checked := make(map[int]bool, len(order))
	if len(order) > maxFrontChecks {
		order = order[:maxFrontChecks]
	}
	frontCells := make([]pair, len(order))
	frontOut := make([]Result, len(order))
	for k, i := range order {
		frontCells[k] = cells[i]
		checked[i] = true
	}
	runCells(ctx, frontCells, opts.Concurrency, func(cell pair) Result {
		attempt := opts
		attempt.SNI = cell.sni
		return Probe(ctx, cell.ip, attempt)
	}, frontOut)
	for k, i := range order {
		handshakes[i] = frontOut[k]
	}
	for i, res := range handshakes {
		if res.Err == "" && !checked[i] {
			handshakes[i].Err = "handshake answered; not front-checked"
		}
	}

	results := append(handshakes, failed...)
	sortResults(results)
	return results
}

// candidateSNIsWithPool is the name list of a matrix scan, which by definition wants every name
// unless the caller named the exact list.
func candidateSNIsWithPool(opts Options) []string {
	if len(opts.SNIs) > 0 {
		return candidateSNIs(opts)
	}
	full := opts
	full.AllSNIs = true
	return candidateSNIs(full)
}

// runCells probes cells concurrently into out, in cell order.
func runCells(ctx context.Context, cells []pair, concurrency int, probe func(pair) Result, out []Result) {
	if concurrency <= 0 {
		concurrency = 12
	}
	var wg sync.WaitGroup
	sem := make(chan struct{}, concurrency)
	for i, cell := range cells {
		out[i] = Result{IP: cell.ip, SNI: cell.sni, Err: "not tried"}
		wg.Add(1)
		go func(i int, cell pair) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if ctx.Err() != nil {
				out[i].Err = trimErr(ctx.Err())
				return
			}
			out[i] = probe(cell)
		}(i, cell)
	}
	wg.Wait()
}

// sortResults puts the working cells first: fronted before refused, answered before silent,
// fastest first within each kind.
func sortResults(results []Result) {
	sort.SliceStable(results, func(i, j int) bool {
		a, b := results[i], results[j]
		if a.Fronted != b.Fronted {
			return a.Fronted
		}
		if (a.Err == "") != (b.Err == "") {
			return a.Err == ""
		}
		return a.RTTMs < b.RTTMs
	})
}

// dialAddr adds the HTTPS port to a bare address.
func dialAddr(ip string) string {
	if _, _, err := net.SplitHostPort(ip); err != nil {
		return net.JoinHostPort(ip, "443")
	}
	return ip
}

// dialTLS connects and handshakes, budgeting each stage its own full Timeout. The returned
// result carries the handshake latency and, on failure, the stage that failed.
func dialTLS(ctx context.Context, ip string, opts Options) (*tls.Conn, Result) {
	res := Result{IP: ip, SNI: opts.SNI}
	addr := dialAddr(ip)
	tlsCfg := &tls.Config{
		ServerName:         opts.SNI,
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: opts.Insecure,
		RootCAs:            opts.RootCAs,
	}
	started := time.Now()
	dialCtx, cancelDial := context.WithTimeout(ctx, opts.Timeout)
	rawConn, err := protect.Dial(dialCtx, "tcp", addr)
	cancelDial()
	if err != nil {
		res.Err = "connect: " + trimErr(err)
		return nil, res
	}
	conn := tls.Client(rawConn, tlsCfg)
	handshakeCtx, cancelHandshake := context.WithTimeout(ctx, opts.Timeout)
	err = conn.HandshakeContext(handshakeCtx)
	cancelHandshake()
	if err != nil {
		_ = rawConn.Close()
		res.Err = "tls: " + trimErr(err)
		return nil, res
	}
	res.RTTMs = time.Since(started).Milliseconds()
	return conn, res
}

// probeHandshake is the cheap half of a probe: connect and handshake only.
func probeHandshake(ctx context.Context, ip string, opts Options) Result {
	opts.withDefaults()
	conn, res := dialTLS(ctx, ip, opts)
	if conn != nil {
		_ = conn.Close()
	}
	return res
}

// Probe checks one address under one name: a TCP + TLS handshake with the Google SNI, then a
// fronted request. Each stage gets the full Timeout.
func Probe(ctx context.Context, ip string, opts Options) Result {
	opts.withDefaults()
	conn, res := dialTLS(ctx, ip, opts)
	if conn == nil {
		return res
	}

	// The request that matters is the one the door needs: Host of the door over a connection whose
	// certificate name is Google. An edge that answers 421 does not front, and is useless here.
	// The stage gets its full Timeout, shortened only by a caller's sooner deadline: a race that
	// another pair already won should not leave this connection reading until the stage runs out.
	readDeadline := time.Now().Add(opts.Timeout)
	if deadline, ok := ctx.Deadline(); ok && deadline.Before(readDeadline) {
		readDeadline = deadline
	}
	_ = conn.SetDeadline(readDeadline)
	req := fmt.Sprintf("GET / HTTP/1.1\r\nHost: %s\r\nUser-Agent: guts-scan/0.2\r\nConnection: close\r\n\r\n", opts.FrontHost)
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

// readStatus reads the status code of an HTTP/1 response without buffering its body. The first
// read is not trusted to carry the whole status line: on a throttled line the line can arrive a
// few bytes at a time, and an edge that did answer must not be reported silent for that.
func readStatus(conn net.Conn) int {
	buf := make([]byte, 0, 64)
	chunk := make([]byte, 32)
	for len(buf) < 12 && len(buf) < 64 {
		n, err := conn.Read(chunk)
		if n > 0 {
			buf = append(buf, chunk[:n]...)
		}
		if err != nil {
			break
		}
	}
	if len(buf) < 12 {
		return 0
	}
	// "HTTP/1.1 404 Not Found"
	line := string(buf)
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
