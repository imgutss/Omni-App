// Package config is what one guts client needs to know, in a file or in a guts:// URI.
//
// The URI is how a phone gets a working setup: the app scans a QR, or opens a link, and holds the
// same fields a command line would take. It never carries a target site, only the door, the key
// and the edge address.
package config

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
)

// Config is one guts client setup.
type Config struct {
	Doors    []string `json:"door"`                // door URLs, in the order to try
	Key      string   `json:"key,omitempty"`       // shared key, also checked by the door
	GoogleIP string   `json:"google_ip,omitempty"` // edge address to dial; empty posts to the door directly
	SNI      string   `json:"sni,omitempty"`       // certificate name used on that connection
	Listen   string   `json:"listen,omitempty"`    // SOCKS5 listen address
	Note     string   `json:"note,omitempty"`      // a label for the phone's list
}

// Defaults fills what a caller may leave out.
func (c *Config) Defaults() {
	if len(c.Doors) == 0 {
		c.Doors = nil
	}
	if c.SNI == "" {
		c.SNI = "www.google.com"
	}
	if c.Listen == "" {
		c.Listen = "127.0.0.1:1080"
	}
}

// Validate reports what would make this setup unusable.
func (c Config) Validate() error {
	if len(c.Doors) == 0 {
		return errors.New("guts: a door URL is required")
	}
	for _, d := range c.Doors {
		u, err := url.Parse(d)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
			return fmt.Errorf("guts: not a door URL: %q", d)
		}
	}
	return nil
}

// URI is the guts:// form of this setup, which a QR code can carry.
func (c Config) URI() (string, error) {
	if err := c.Validate(); err != nil {
		return "", err
	}
	body, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return "guts://" + base64.RawURLEncoding.EncodeToString(body), nil
}

// ParseURI reads a guts:// URI back into a setup.
func ParseURI(raw string) (Config, error) {
	var c Config
	s := strings.TrimSpace(raw)
	if s == "" {
		return c, errors.New("guts: empty URI")
	}
	if !strings.HasPrefix(strings.ToLower(s), "guts://") {
		return c, errors.New("guts: not a guts:// URI")
	}
	body := s[len("guts://"):]
	body = strings.TrimSuffix(body, "/")
	decoded, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		// Some scanners keep the padding.
		decoded, err = base64.URLEncoding.DecodeString(body)
		if err != nil {
			return c, fmt.Errorf("guts: URI body is not base64: %w", err)
		}
	}
	if err := json.Unmarshal(decoded, &c); err != nil {
		return c, fmt.Errorf("guts: URI body is not a setup: %w", err)
	}
	if err := c.Validate(); err != nil {
		return c, err
	}
	c.Defaults()
	return c, nil
}
