package service

import (
	"testing"

	"github.com/armanlife/arman-vpn-client/core/internal/proxy"
)

func stepByID(rep doctorReport, id string) doctorStep {
	for _, s := range rep.Steps {
		if s.ID == id {
			return s
		}
	}
	return doctorStep{}
}

// A healthy running path produces all-ok steps and no recommendation.
func TestDoctorHealthyPath(t *testing.T) {
	limit, remaining := int64(50<<30), int64(40<<30)
	view := StatusView{
		State:         engineState{Phase: PhaseRunning},
		ActiveAccount: &AccountView{Email: "me@example.com"},
		Accounts:      map[string]int{"ready": 1},
		Pass: &proxy.RuntimeStatus{
			Accepting: true, QuotaLimitBytes: &limit, QuotaRemainingBytes: &remaining,
			SecondsUntilExpiry: 3600,
		},
		Proxy:    &proxyView{Transport: "h2"},
		LastTest: &proxy.ConnectionTestResult{OK: true, TunnelMS: 40, TotalMS: 120, ExitIPv4: "1.2.3.4"},
	}
	rep := buildDoctor(view, 30, true, false, "4/4 names resolved")
	if !rep.OK {
		t.Fatalf("healthy path should be ok: %+v", rep)
	}
	if rep.Recommendation != "" {
		t.Fatalf("healthy path needs no recommendation, got %q", rep.Recommendation)
	}
	if stepByID(rep, "tunnel").Status != doctorOK || stepByID(rep, "exit").Status != doctorOK {
		t.Fatalf("tunnel/exit should be ok: %+v", rep.Steps)
	}
}

// No account: the account step fails and the recommendation is to sign in.
func TestDoctorNoAccount(t *testing.T) {
	view := StatusView{State: engineState{Phase: PhaseWaiting, Code: CodeNoAccounts}}
	rep := buildDoctor(view, 0, false, false, "no bootstrap name resolved")
	if rep.OK {
		t.Fatal("no account must not be ok")
	}
	if stepByID(rep, "account").Status != doctorFail {
		t.Fatalf("account step should fail: %+v", rep.Steps)
	}
	if rep.Recommendation != "sign_in" {
		t.Fatalf("recommendation = %q, want sign_in", rep.Recommendation)
	}
}

// Exhausted quota is a hard fail with the quota recommendation.
func TestDoctorQuotaExhausted(t *testing.T) {
	limit, remaining := int64(50<<30), int64(0)
	view := StatusView{
		State:         engineState{Phase: PhaseRunning},
		ActiveAccount: &AccountView{Email: "me@example.com"},
		Pass:          &proxy.RuntimeStatus{Accepting: true, QuotaLimitBytes: &limit, QuotaRemainingBytes: &remaining},
		Proxy:         &proxyView{Transport: "h2"},
		LastTest:      &proxy.ConnectionTestResult{OK: true, ExitIPv4: "1.2.3.4"},
	}
	rep := buildDoctor(view, 30, true, false, "4/4")
	if stepByID(rep, "quota").Status != doctorFail {
		t.Fatalf("quota step should fail: %+v", rep.Steps)
	}
	if rep.Recommendation != "quota_exhausted" {
		t.Fatalf("recommendation = %q, want quota_exhausted", rep.Recommendation)
	}
}

// A filtered system DNS is a warning, not a hard failure, when the tunnel works.
func TestDoctorFilteredDNSIsWarning(t *testing.T) {
	view := StatusView{
		State:         engineState{Phase: PhaseRunning},
		ActiveAccount: &AccountView{Email: "me@example.com"},
		Proxy:         &proxyView{Transport: "h2"},
		LastTest:      &proxy.ConnectionTestResult{OK: true, ExitIPv4: "1.2.3.4"},
	}
	rep := buildDoctor(view, 30, true, true, "4/4")
	if stepByID(rep, "dns").Status != doctorWarn {
		t.Fatalf("filtered dns should warn: %+v", rep.Steps)
	}
	if rep.Recommendation != "dns_filtered" {
		t.Fatalf("recommendation = %q, want dns_filtered", rep.Recommendation)
	}
}

func TestDNAVerdict(t *testing.T) {
	cases := []struct {
		name string
		dna  networkDNA
		want string
	}{
		{"healthy", networkDNA{Targets: 4, BootstrapResolved: 4, SystemResolved: 4}, "healthy"},
		{"filtered", networkDNA{Targets: 4, BootstrapResolved: 4, SystemResolved: 4, Filtered: true}, "dns_filtered"},
		{"carrier", networkDNA{Targets: 4, BootstrapResolved: 0, SystemResolved: 4}, "encrypted_dns_blocked_use_carrier"},
		{"relay", networkDNA{Targets: 4, BootstrapResolved: 0, SystemResolved: 0}, "bootstrap_blocked_use_relay"},
	}
	for _, c := range cases {
		if got := dnaVerdict(c.dna); got != c.want {
			t.Errorf("%s: dnaVerdict = %q, want %q", c.name, got, c.want)
		}
	}
}
