package relay

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"guts/protect"
)

// GoogleDirectDoer carries one already-decrypted browser request to a Google-owned destination
// without asking Apps Script to fetch it.
//
// This is deliberately separate from Door: a Door speaks the guts protocol to the user's Apps
// Script, while a GoogleDirectDoer speaks ordinary HTTP to Google's own frontend. YouTube treats
// UrlFetchApp's datacenter traffic as automated and answers with its sorry page; the same request
// sent from the phone to a Google edge, with the browser's own headers and cookies, is the request
// the browser meant to make.
type GoogleDirectDoer interface {
	Do(req *http.Request) (*http.Response, error)
	Describe() string
}

// GoogleDirectFetcher reaches Google-owned HTTPS hosts through one Google edge address.
//
// The TCP connection always goes to the edge. TLS uses the profile's already-scanned Google SNI,
// while the encrypted HTTP request keeps the real destination in its URL/Host authority. That is
// the same frontend behaviour the Apps Script door relies on, reused for the destinations Apps
// Script itself cannot fetch well: YouTube, googlevideo, Google static assets and Google APIs.
type GoogleDirectFetcher struct {
	edge string
	sni  string
	http *http.Client
}

// NewGoogleDirectFetcher builds the direct Google path for fetch mode.
func NewGoogleDirectFetcher(edgeAddr, sni string) (*GoogleDirectFetcher, error) {
	if edgeAddr == "" {
		return nil, errors.New("guts: a google edge address is required for direct Google fetch")
	}
	if sni == "" {
		sni = "www.google.com"
	}
	addr := edgeAddr
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(addr, "443")
	}
	transport := &http.Transport{
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   32,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   30 * time.Second,
		ResponseHeaderTimeout: 90 * time.Second,
		ExpectContinueTimeout: time.Second,
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return protect.Dial(ctx, network, addr)
		},
		TLSClientConfig: &tls.Config{
			ServerName: sni,
			MinVersion: tls.VersionTLS12,
		},
	}
	return &GoogleDirectFetcher{
		edge: addr,
		sni:  sni,
		http: &http.Client{
			Transport: transport,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

func (g *GoogleDirectFetcher) Do(req *http.Request) (*http.Response, error) {
	return g.http.Do(req)
}

func (g *GoogleDirectFetcher) Describe() string {
	return fmt.Sprintf("google-direct edge %s (sni %s)", g.edge, g.sni)
}

// UseGoogleDirect lets fetch mode send Google-owned HTTPS destinations to Google's edge directly
// instead of spending an Apps Script fetch on them.
func (p *Proxy) UseGoogleDirect(direct GoogleDirectDoer) {
	p.mu.Lock()
	p.googleDirect = direct
	p.mu.Unlock()
}

func (p *Proxy) googleFetcher() GoogleDirectDoer {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.googleDirect
}

var googleDirectSuffixes = []string{
	"google.com",
	"googleapis.com",
	"gstatic.com",
	"googleusercontent.com",
	"usercontent.goog",
	"youtube.com",
	"youtu.be",
	"googlevideo.com",
	"ytimg.com",
	"ggpht.com",
	"youtubekids.com",
	"youtube-nocookie.com",
	"youtubeeducation.com",
	"google-analytics.com",
	"googletagmanager.com",
	"doubleclick.net",
	"googleadservices.com",
	"googlesyndication.com",
	"2mdn.net",
	"gvt1.com",
	"gvt2.com",
	"gvt3.com",
	"withgoogle.com",
}

func isGoogleDirectHost(host string) bool {
	host = normalizeTLSHost(host)
	if host == "" || net.ParseIP(host) != nil {
		return false
	}
	for _, suffix := range googleDirectSuffixes {
		if host == suffix || strings.HasSuffix(host, "."+suffix) {
			return true
		}
	}
	parts := strings.Split(host, ".")
	if len(parts) >= 2 && parts[len(parts)-2] == "google" {
		return true // google.de, www.google.fr, mail.google.com and their subdomains
	}
	if len(parts) >= 3 && parts[len(parts)-3] == "google" {
		switch parts[len(parts)-2] {
		case "co", "com":
			return true // google.co.uk, google.com.tr and their subdomains
		}
	}
	return false
}
