package service

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Connection Doctor: a step-by-step diagnosis of the Firefox path, from the
// engine up to a verified exit. Each step carries a stable id (the app words
// it in its own language), a status, and a short technical detail. The report
// ends with one recommendation the app can show as a single next action.
//
// It is built from the live status plus a DNS probe, and optionally a full
// connection test (?test=1) when the proxy is running. It never sends account
// secrets anywhere: it only reads what the engine already knows and resolves
// the same public names the bootstrap resolves.

// Doctor step statuses.
const (
	doctorOK      = "ok"
	doctorFail    = "fail"
	doctorWarn    = "warn"
	doctorSkip    = "skip"
	doctorPending = "pending"
)

type doctorStep struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Detail string `json:"detail,omitempty"`
	MS     int64  `json:"ms,omitempty"`
}

type doctorReport struct {
	At             time.Time    `json:"at"`
	Steps          []doctorStep `json:"steps"`
	OK             bool         `json:"ok"`
	Summary        string       `json:"summary"`
	Recommendation string       `json:"recommendation,omitempty"`
}

// handleDoctor answers GET /api/doctor. With ?test=1 it also runs a live
// connection test through the tunnel; otherwise it uses the last test result
// so the diagnosis is quick and does not wake a sleeping proxy.
func (s *dashboardServer) handleDoctor(w http.ResponseWriter, r *http.Request) {
	view, err := s.d.StatusView()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	live := r.URL.Query().Get("test") == "1"
	if live && view.State.Phase == PhaseRunning {
		if result, err := s.d.ConnectionTest(); err == nil {
			view.LastTest = &result
		}
	}
	s.d.mu.Lock()
	countries := len(s.d.countries)
	s.d.mu.Unlock()
	dnsOK, dnsFiltered, dnsDetail := probeDNSForDoctor(s.d)
	report := buildDoctor(view, countries, dnsOK, dnsFiltered, dnsDetail)
	writeJSON(w, http.StatusOK, report)
}

// buildDoctor assembles the ordered steps and the single recommendation. It
// is pure so the diagnosis can be tested without a running engine.
func buildDoctor(view StatusView, countries int, dnsOK, dnsFiltered bool, dnsDetail string) doctorReport {
	rep := doctorReport{At: time.Now(), OK: true}
	add := func(id, status, detail string, ms int64) {
		rep.Steps = append(rep.Steps, doctorStep{ID: id, Status: status, Detail: detail, MS: ms})
		if status == doctorFail {
			rep.OK = false
		}
	}

	// 1. Engine up.
	switch view.State.Phase {
	case PhaseRunning:
		add("engine", doctorOK, view.State.Message, 0)
	case PhaseStarting:
		add("engine", doctorPending, view.State.Message, 0)
	case PhaseOff:
		add("engine", doctorSkip, "proxy is off", 0)
	default:
		add("engine", doctorFail, view.State.Code+": "+view.State.Message, 0)
	}

	// 2. Signed in.
	if view.ActiveAccount != nil && view.ActiveAccount.Email != "" {
		add("account", doctorOK, view.ActiveAccount.Email, 0)
	} else if len(view.Accounts) > 0 {
		add("account", doctorWarn, "accounts present but none active", 0)
	} else {
		add("account", doctorFail, "no account signed in", 0)
	}

	// 3. Entitlement / Guardian activated and 4. ProxyPass + quota.
	if view.Pass != nil {
		if view.Pass.Accepting {
			add("entitlement", doctorOK, "Guardian pass accepting", 0)
		} else {
			add("entitlement", doctorWarn, view.Pass.Reason, 0)
		}
		if view.Pass.QuotaUnlimited {
			add("quota", doctorOK, "unlimited", 0)
		} else if view.Pass.QuotaLimitBytes != nil && *view.Pass.QuotaLimitBytes > 0 {
			remaining := int64(0)
			if view.Pass.QuotaRemainingBytes != nil {
				remaining = *view.Pass.QuotaRemainingBytes
			}
			if remaining <= 0 {
				add("quota", doctorFail, "quota exhausted", 0)
			} else {
				add("quota", doctorOK, "quota available", 0)
			}
		} else {
			add("quota", doctorWarn, "quota not read yet", 0)
		}
		if view.Pass.SecondsUntilExpiry > 0 {
			add("pass", doctorOK, "pass valid", 0)
		} else {
			add("pass", doctorWarn, "pass expiry unknown", 0)
		}
	} else {
		add("entitlement", doctorSkip, "no pass yet", 0)
		add("quota", doctorSkip, "no pass yet", 0)
		add("pass", doctorSkip, "no pass yet", 0)
	}

	// 5. Server list.
	if countries > 0 {
		add("serverlist", doctorOK, itoa(countries)+" locations", 0)
	} else {
		add("serverlist", doctorFail, "server list not loaded", 0)
	}

	// 6. DNS bootstrap / leak. The DNS probe resolves the bootstrap names and
	// flags a tampered system answer (the Irancell signature).
	switch {
	case dnsFiltered:
		add("dns", doctorWarn, "system DNS is filtered; encrypted/carrier DNS in use", 0)
	case dnsOK:
		add("dns", doctorOK, dnsDetail, 0)
	default:
		add("dns", doctorFail, dnsDetail, 0)
	}

	// 7-9. Tunnel, exit IP, country match — from the last connection test.
	if view.State.Phase != PhaseRunning {
		add("tunnel", doctorSkip, "proxy not running", 0)
		add("exit", doctorSkip, "proxy not running", 0)
		add("country", doctorSkip, "proxy not running", 0)
	} else if view.LastTest == nil {
		add("tunnel", doctorPending, "no test yet", 0)
		add("exit", doctorPending, "no test yet", 0)
		add("country", doctorPending, "no test yet", 0)
	} else {
		t := view.LastTest
		if t.OK {
			add("tunnel", doctorOK, "H2 CONNECT "+itoa64(t.TunnelMS)+"ms", t.TotalMS)
		} else {
			add("tunnel", doctorFail, t.ErrorCode+": "+t.Error, t.TotalMS)
		}
		if t.ExitIPv4 != "" || t.ExitIPv6 != "" {
			add("exit", doctorOK, strings.TrimSpace(t.ExitIPv4+" "+t.ExitIPv6), 0)
		} else {
			add("exit", doctorWarn, "exit IP not verified", 0)
		}
		if view.ExitMismatch != nil {
			add("country", doctorWarn, "exit country differs from selection", 0)
		} else {
			add("country", doctorOK, "exit matches selection", 0)
		}
	}

	// 10. Transport / UDP-QUIC note.
	if view.Proxy != nil && view.Proxy.Transport != "" {
		add("transport", doctorOK, view.Proxy.Transport, 0)
	} else {
		add("transport", doctorSkip, "no upstream session", 0)
	}

	rep.Summary, rep.Recommendation = doctorVerdict(view, dnsFiltered)
	return rep
}

// probeDNSForDoctor resolves the bootstrap names and reports whether they
// resolve, and whether the system answer is a filtered (tampered) one.
func probeDNSForDoctor(d *Daemon) (ok, filtered bool, detail string) {
	cfg := d.config()
	ctx, cancel := context.WithTimeout(context.Background(), dnsTestTimeout)
	defer cancel()
	results, err := d.testDNS(ctx, cfg.DNS.Servers)
	if err != nil {
		return false, false, err.Error()
	}
	resolved := 0
	for _, r := range results {
		if r.Filtered {
			filtered = true
		}
		if len(r.Addrs) > 0 || len(r.SystemAddrs) > 0 {
			resolved++
		}
	}
	if resolved == 0 {
		return false, filtered, "no bootstrap name resolved"
	}
	return true, filtered, itoa(resolved) + "/" + itoa(len(results)) + " names resolved"
}

// doctorVerdict picks the single next action from the first failing area.
func doctorVerdict(view StatusView, dnsFiltered bool) (summary, recommendation string) {
	switch {
	case view.NoNetwork:
		return "No network connection.", "connect_network"
	case view.State.Phase == PhaseOff:
		return "The proxy is off.", "turn_on"
	case view.State.Code == CodeNoAccounts:
		return "No Firefox account is signed in.", "sign_in"
	case view.State.Code == CodeNoUsableAccount:
		return "No account can be used right now.", "account_unusable"
	case view.ActiveAccount == nil || view.ActiveAccount.Email == "":
		return "No Firefox account is signed in.", "sign_in"
	case view.State.Phase != PhaseRunning && view.State.Phase != PhaseStarting:
		return "The engine is not running.", "restart_engine"
	case view.Pass != nil && !view.Pass.QuotaUnlimited && view.Pass.QuotaLimitBytes != nil &&
		view.Pass.QuotaRemainingBytes != nil && *view.Pass.QuotaRemainingBytes <= 0:
		return "The account's quota is exhausted.", "quota_exhausted"
	case view.LastTest != nil && !view.LastTest.OK && view.State.Phase == PhaseRunning:
		return "The tunnel could not carry traffic.", "diagnose_network"
	case dnsFiltered:
		return "Connected, but the network filters DNS.", "dns_filtered"
	case view.ExitMismatch != nil:
		return "Connected, but the exit country differs.", "exit_mismatch"
	case view.State.Phase == PhaseRunning:
		return "The Firefox path is healthy.", ""
	default:
		return "The engine is starting.", ""
	}
}

func itoa(n int) string { return strconv.Itoa(n) }

func itoa64(n int64) string { return strconv.FormatInt(n, 10) }
