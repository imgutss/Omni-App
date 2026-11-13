package main

import (
	_ "embed"
	"os"
	"path/filepath"
	"strings"
)

// extraRoots are root CAs the Mozilla and Fastly servers chain to (GlobalSign Root R46 and E46) that
// older Android system stores lack. Taken from the Windows root store; their SHA-1 fingerprints match
// Android's own copies.
//
//go:embed extra-roots.pem
var extraRoots []byte

const (
	// Android's updatable store (Android 10+), which Android itself uses in place of the system one
	// when present. Go reads only the latter.
	apexCerts   = "/apex/com.android.conscrypt/cacerts"
	systemCerts = "/system/etc/security/cacerts"
	userCerts   = "/data/misc/keychain/certs-added"

	// The directories a desktop Linux distribution keeps its roots in; when they are all missing, this
	// is not a desktop.
	etcCerts = "/etc/ssl/certs"
)

// useDeviceRoots points Go's certificate verification at the roots the phone trusts plus extraRoots,
// through SSL_CERT_DIR and SSL_CERT_FILE. Go reads them once, at the first verification, so this runs
// before the daemon starts.
//
// The engine is built for Linux and executed from an app's native library directory, because Android
// refuses to run a Go binary that is not linked against bionic from an app directory — so the runtime
// says "linux" while the store in front of it is Android's, and the directories Go looks for by
// default are not there. On a real desktop this leaves the system store alone.
func useDeviceRoots(dataDir string) error {
	if dirExists(etcCerts) {
		return nil
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return err
	}
	file := filepath.Join(dataDir, "extra-roots.pem")
	if err := os.WriteFile(file, extraRoots, 0o600); err != nil {
		return err
	}
	if err := os.Setenv("SSL_CERT_DIR", strings.Join([]string{certStore(), userCerts}, ":")); err != nil {
		return err
	}
	return os.Setenv("SSL_CERT_FILE", file)
}

func certStore() string {
	if dirExists(apexCerts) {
		return apexCerts
	}
	return systemCerts
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}
