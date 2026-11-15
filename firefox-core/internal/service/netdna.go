package service

import (
	"context"
	"net/http"
	"time"

	"github.com/armanlife/arman-vpn-client/core/internal/resolver"
)

// Network DNA describes the network the phone is on right now, as far as the
// Firefox bootstrap can tell: which DNS paths answer, whether the system DNS
// is tampered with (the Irancell signature), and which rescue paths are armed.
// The app pairs this with the carrier name it reads from the phone and shows
// one plain sentence about why a connection does or does not come up.
//
// It resolves only the public bootstrap names and reads local configuration;
// it sends no account data anywhere.

type networkDNA struct {
	At               time.Time `json:"at"`
	DNSServers       []string  `json:"dns_servers"`
	SystemServers    []string  `json:"system_servers"`
	FallbackToSystem bool      `json:"fallback_to_system"`
	Resolver         string    `json:"resolver"`
	// Filtered is set when the system resolver answers a bootstrap name with
	// a block-page address: the network tampers with plain DNS.
	Filtered bool `json:"filtered"`
	// BootstrapResolved / SystemResolved count the bootstrap names that
	// resolved through the configured servers and through the system.
	BootstrapResolved int `json:"bootstrap_resolved"`
	SystemResolved    int `json:"system_resolved"`
	Targets           int `json:"targets"`
	// SharedRelayEnabled: the bootstrap relay rescue is armed.
	SharedRelayEnabled bool   `json:"shared_relay_enabled"`
	UpstreamProxy      string `json:"upstream_proxy,omitempty"`
	// Recommendation is a stable code the app words in its own language.
	Recommendation string `json:"recommendation"`
}

// handleNetworkDNA answers GET /api/network.
func (s *dashboardServer) handleNetworkDNA(w http.ResponseWriter, r *http.Request) {
	cfg := s.d.config()
	dna := networkDNA{
		At:                 time.Now(),
		DNSServers:         cfg.DNS.Servers,
		SystemServers:      resolver.SystemServers(),
		FallbackToSystem:   cfg.DNS.FallbackToSystem,
		Resolver:           dnsDescription(cfg.DNS),
		SharedRelayEnabled: cfg.Network.SharedRelayInUse(),
		UpstreamProxy:      cfg.Network.UpstreamProxyInUse(),
	}
	ctx, cancel := context.WithTimeout(r.Context(), dnsTestTimeout)
	defer cancel()
	if results, err := s.d.testDNS(ctx, cfg.DNS.Servers); err == nil {
		dna.Targets = len(results)
		for _, res := range results {
			if res.Filtered {
				dna.Filtered = true
			}
			if len(res.Addrs) > 0 {
				dna.BootstrapResolved++
			}
			if len(res.SystemAddrs) > 0 {
				dna.SystemResolved++
			}
		}
	}
	dna.Recommendation = dnaVerdict(dna)
	writeJSON(w, http.StatusOK, dna)
}

// dnaVerdict picks the one thing to say about this network.
func dnaVerdict(dna networkDNA) string {
	switch {
	case dna.Targets > 0 && dna.BootstrapResolved == 0 && dna.SystemResolved == 0:
		// Nothing resolves the bootstrap names: the relay rescue is the way.
		return "bootstrap_blocked_use_relay"
	case dna.Filtered:
		return "dns_filtered"
	case dna.BootstrapResolved == 0 && dna.SystemResolved > 0:
		return "encrypted_dns_blocked_use_carrier"
	default:
		return "healthy"
	}
}
