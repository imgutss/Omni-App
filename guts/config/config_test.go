package config_test

import (
	"strings"
	"testing"

	"guts/config"
)

func TestURIRoundTrip(t *testing.T) {
	want := config.Config{
		Doors:    []string{"https://script.google.com/macros/s/AAA/exec", "https://exit.example/g"},
		Key:      "a-long-secret",
		GoogleIP: "216.239.38.120",
		SNI:      "www.google.com",
		Listen:   "127.0.0.1:1080",
	}
	uri, err := want.URI()
	if err != nil {
		t.Fatalf("uri: %v", err)
	}
	if !strings.HasPrefix(uri, "guts://") {
		t.Fatalf("uri has no scheme: %q", uri)
	}
	got, err := config.ParseURI(uri)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(got.Doors) != len(want.Doors) || got.Doors[0] != want.Doors[0] {
		t.Fatalf("doors differ: %v", got.Doors)
	}
	if got.Key != want.Key || got.GoogleIP != want.GoogleIP || got.SNI != want.SNI || got.Listen != want.Listen {
		t.Fatalf("fields differ: %+v", got)
	}
}

func TestURIDefaultsWhatIsMissing(t *testing.T) {
	c := config.Config{Doors: []string{"https://door.example/exec"}}
	uri, err := c.URI()
	if err != nil {
		t.Fatalf("uri: %v", err)
	}
	got, err := config.ParseURI(uri)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got.SNI != "www.google.com" {
		t.Fatalf("sni default missing: %q", got.SNI)
	}
	if got.Listen != "127.0.0.1:1080" {
		t.Fatalf("listen default missing: %q", got.Listen)
	}
}

func TestBrokenSetupsAreRefused(t *testing.T) {
	if _, err := (config.Config{}).URI(); err == nil {
		t.Fatal("a setup without a door was accepted")
	}
	if _, err := (config.Config{Doors: []string{"ftp://x"}}).URI(); err == nil {
		t.Fatal("a non-http door was accepted")
	}
	if _, err := config.ParseURI("guts://not-base64!!"); err == nil {
		t.Fatal("a broken URI was accepted")
	}
	if _, err := config.ParseURI("https://script.google.com/macros/s/AAA/exec"); err == nil {
		t.Fatal("a plain URL was accepted as a guts URI")
	}
}
