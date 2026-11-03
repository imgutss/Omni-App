// Command gutsclient runs the guts SOCKS5 proxy on this machine: every CONNECT it receives is
// carried through a door to the exit node, which opens the real connection.
//
//	gutsclient -door https://script.google.com/macros/s/<id>/exec -key <secret> \
//	           -google-ip auto -sni www.google.com -listen 127.0.0.1:1080
//
// Flags that matter on a restricted network:
//
//	-scan          probe the Google frontend addresses and print which ones carry a fronted call
//	-google-ip auto  scan first, then use the fastest edge address that worked
//	-door (repeat)   several doors, tried fastest first and rotated on failure
//	-export        print a guts:// URI (a QR code can carry it) instead of starting the proxy
//	-import        start from a guts:// URI
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"guts/config"
	"guts/gip"
	"guts/protect"
	"guts/relay"
)

type stringList []string

func (s *stringList) String() string { return strings.Join(*s, ",") }

func (s *stringList) Set(v string) error {
	for _, part := range strings.Split(v, ",") {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			*s = append(*s, trimmed)
		}
	}
	return nil
}

// firstLine keeps one error reason on one printed line.
func firstLine(text string) string {
	if i := strings.IndexByte(text, '\n'); i >= 0 {
		return text[:i]
	}
	return text
}

// envInt reads an integer from the environment, so the app can configure the client without an argv
// that an older client would refuse to parse.
func envInt(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		return fallback
	}
	return value
}

func main() {
	var doors stringList
	flag.Var(&doors, "door", "door URL, repeatable or comma separated")

	listen := flag.String("listen", "127.0.0.1:1080", "SOCKS5 listen address")
	key := flag.String("key", os.Getenv("GUTS_KEY"), "shared key, also read from GUTS_KEY")
	googleIP := flag.String("google-ip", "", "Google edge address, or 'auto' to scan for the working one")
	sni := flag.String("sni", "www.google.com", "SNI used on the connection to the Google edge")
	frontHost := flag.String("front-host", "script.google.com", "Host header a fronted scan sends")
	direct := flag.Bool("direct", false, "post straight to the exit node at -door, no fronting")
	check := flag.Bool("check", false, "ping the door and exit without starting the proxy")
	scan := flag.Bool("scan", false, "scan the Google frontend addresses and exit")
	probe := flag.Bool("probe", false, "ask whether this network reaches the Google edge at all, print JSON, exit 0 if it does and 3 if it does not")
	probeBudget := flag.Duration("probe-budget", 5*time.Second, "how long a probe may take")
	scanJSON := flag.Bool("scan-json", false, "print the scan as JSON")
	scanSNIs := flag.Bool("scan-snis", false, "try the Google names a fronted call may use, and exit")
	scanSNIsJSON := flag.Bool("scan-snis-json", false, "print the name scan as JSON")
	asJSON := flag.Bool("export-json", false, "print the setup as JSON")
	asURI := flag.Bool("export", false, "print the setup as a guts:// URI")
	importURI := flag.String("import", "", "start from a guts:// URI")
	pick := flag.Bool("pick", false, "ping every door, print the latencies, then continue")
	timeout := flag.Duration("timeout", 4*time.Second, "per-address timeout of a scan")
	doorBudget := flag.Int("door-budget", envInt("GUTS_DOOR_BUDGET", 0), "calls one door may take per day (0 = no limit); spread over the doors so several Apps Script accounts add up")
	quotaState := flag.String("quota-state", os.Getenv("GUTS_QUOTA_STATE"), "file that remembers today's calls per door across restarts (empty = in memory only)")
	mux := flag.Bool("mux", true, "carry every connection as a stream of one session (one door call per interval)")
	controlSocket := flag.String(
		"fd-control-unix-socket",
		os.Getenv("OMNI_FD_CONTROL_UNIX_SOCKET"),
		"Android: abstract unix socket that protects every dialed socket from our own VPN",
	)
	flag.Parse()

	// Set before the first dial, so that a scan or a probe is protected too: inside the VPN an
	// unprotected connection to a Google edge address would be routed back into our own tunnel.
	if err := protect.Use(*controlSocket); err != nil {
		log.Fatalf("gutsclient: %v", err)
	}
	if address := protect.Address(); address != "" {
		log.Printf("gutsclient: protecting sockets through %s", address)
	}

	if *importURI != "" {
		cfg, err := config.ParseURI(*importURI)
		if err != nil {
			log.Fatalf("gutsclient: %v", err)
		}
		if len(doors) == 0 {
			doors = cfg.Doors
		}
		if *key == "" {
			*key = cfg.Key
		}
		if *googleIP == "" {
			*googleIP = cfg.GoogleIP
		}
		if *sni == "www.google.com" && cfg.SNI != "" {
			*sni = cfg.SNI
		}
		if *listen == "127.0.0.1:1080" && cfg.Listen != "" {
			*listen = cfg.Listen
		}
	}

	if *probe {
		// The one question a phone asks before it tries: does this network reach Google at all?
		// Nothing here touches Apps Script, so the answer costs no quota.
		ctx, cancel := context.WithTimeout(context.Background(), *probeBudget)
		defer cancel()
		opts := gip.Options{SNI: *sni, FrontHost: *frontHost, Timeout: *timeout}
		if ip := strings.TrimSpace(*googleIP); ip != "" && ip != "auto" {
			if host, err := gip.Host(ip); err == nil {
				opts.Only = []string{host}
			}
		}
		res, reachable := gip.Reachable(ctx, opts)
		out := struct {
			Probe string `json:"probe"`
			OK    bool   `json:"ok"`
			IP    string `json:"ip,omitempty"`
			RTTMs int64  `json:"rtt_ms,omitempty"`
			Error string `json:"e,omitempty"`
		}{Probe: "edge", OK: reachable, IP: res.IP, RTTMs: res.RTTMs, Error: res.Err}
		enc := json.NewEncoder(os.Stdout)
		_ = enc.Encode(out)
		if !reachable {
			os.Exit(3)
		}
		os.Exit(0)
	}

	if *scanSNIs || *scanSNIsJSON {
		// The name is in the clear in the ClientHello, so a network that filters on it answers for
		// some Google names and drops the others. Scanning one edge with every name finds the one
		// this network still lets through.
		ctx, cancel := context.WithTimeout(context.Background(), *probeBudget)
		defer cancel()
		opts := gip.Options{SNI: *sni, FrontHost: *frontHost, Timeout: *timeout}
		edge := strings.TrimSpace(*googleIP)
		if edge == "" || edge == "auto" {
			if best, ok := gip.Reachable(ctx, opts); ok {
				edge = best.IP
			} else {
				edge = gip.Pool[0]
			}
		}
		host, err := gip.Host(edge)
		if err != nil {
			log.Fatalf("gutsclient: %v", err)
		}
		results := gip.ScanSNIs(ctx, host, nil, opts)
		if *scanSNIsJSON {
			enc := json.NewEncoder(os.Stdout)
			enc.SetIndent("", "  ")
			_ = enc.Encode(results)
			return
		}
		for _, res := range results {
			if !res.Fronted {
				fmt.Printf("  %-28s blocked (%s)\n", res.SNI, firstLine(res.Err))
				continue
			}
			fmt.Printf("  %-28s %d ms\n", res.SNI, res.RTTMs)
		}
		if best := gip.BestSNI(results); best != "" {
			fmt.Println("best:", best)
		}
		return
	}

	if *scan || *scanJSON || *googleIP == "auto" {
		results := gip.Scan(context.Background(), gip.Options{SNI: *sni, FrontHost: *frontHost, Timeout: *timeout})
		if *scanJSON {
			enc := json.NewEncoder(os.Stdout)
			enc.SetIndent("", "  ")
			_ = enc.Encode(results)
		} else {
			printScan(results)
		}
		// An explicit scan answers and exits; -google-ip=auto is the client's own startup path and
		// continues with the best address it found.
		if *scan || *scanJSON {
			return
		}
		best := gip.Best(results)
		if best == "" {
			log.Fatal("gutsclient: no Google edge address carried a fronted call; the door is unreachable from here")
		}
		*googleIP = best
		log.Printf("gutsclient: using edge %s", best)
	}

	if len(doors) == 0 {
		log.Fatal("gutsclient: -door is required")
	}

	if *asURI || *asJSON {
		cfg := config.Config{
			Doors:    doors,
			Key:      *key,
			GoogleIP: *googleIP,
			SNI:      *sni,
			Listen:   *listen,
		}
		cfg.Defaults()
		if *asJSON {
			out, _ := json.MarshalIndent(cfg, "", "  ")
			fmt.Println(string(out))
			return
		}
		uri, err := cfg.URI()
		if err != nil {
			log.Fatalf("gutsclient: %v", err)
		}
		fmt.Println(uri)
		return
	}

	var built []relay.Door
	for _, raw := range doors {
		parsed, err := url.Parse(raw)
		if err != nil {
			log.Fatalf("gutsclient: bad -door %q: %v", raw, err)
		}
		if *direct || *googleIP == "" {
			built = append(built, relay.NewDirect(raw, *key))
			continue
		}
		fronted, err := relay.NewFronted(parsed, *googleIP, *sni, *key)
		if err != nil {
			log.Fatalf("gutsclient: %v", err)
		}
		built = append(built, fronted)
	}
	rotator := relay.NewRotator(built...)
	var door relay.Door = rotator
	var quotaDoor *relay.QuotaRotator
	if *doorBudget > 0 || *quotaState != "" {
		quotaDoor = relay.NewQuotaRotator(rotator, relay.NewQuota(*doorBudget, *quotaState))
		door = quotaDoor
		if *doorBudget > 0 {
			log.Printf("gutsclient: %d calls per door per day (~%d for %d doors), state in %q",
				*doorBudget, *doorBudget*len(built), len(built), *quotaState)
		}
	}
	if quotaDoor != nil {
		defer quotaDoor.Close()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if *check {
		info, err := relay.Check(ctx, door)
		if err != nil {
			fmt.Println("door check failed:", err)
			os.Exit(1)
		}
		fmt.Println("door ok:", info, "via", door.Describe())
		return
	}
	if *pick && len(built) > 1 {
		for name, took := range rotator.Pick(ctx) {
			if took < 0 {
				fmt.Printf("  %-60s unreachable\n", name)
				continue
			}
			fmt.Printf("  %-60s %d ms\n", name, took.Milliseconds())
		}
	}

	proxy := relay.NewProxy(door, *listen, func(format string, args ...any) { log.Printf(format, args...) })
	// One session for every connection: the difference between one door call per interval and one
	// per connection, which is what an Apps Script quota counts.
	proxy.UseMux(*mux)
	log.Printf("gutsclient: multiplexing %v", *mux)
	addr, err := proxy.Listen()
	if err != nil {
		log.Fatalf("gutsclient: %v", err)
	}
	log.Printf("guts client on socks5://%s via %s", addr, door.Describe())

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		log.Printf("guts client: stopping")
		_ = proxy.Close()
	}()
	if err := proxy.Serve(); err != nil {
		log.Fatalf("gutsclient: %v", err)
	}
}

func printScan(results []gip.Result) {
	fmt.Printf("%-16s %-9s %-8s %s\n", "address", "handshake", "fronted", "note")
	for _, r := range results {
		rtt := "-"
		if r.RTTMs > 0 {
			rtt = fmt.Sprintf("%d ms", r.RTTMs)
		}
		fronted := "no"
		if r.Fronted {
			fronted = "yes"
		}
		note := r.Err
		if note == "" {
			note = fmt.Sprintf("status %d", r.Status)
		}
		fmt.Printf("%-16s %-9s %-8s %s\n", r.IP, rtt, fronted, note)
	}
}
