package relay

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// CertificateAuthority is the user's own local root for fetch mode.
//
// The private key belongs on this device only. It lets the phone terminate an HTTPS connection,
// translate it into an Apps Script fetch, and encrypt the answer again for the browser. It is not
// uploaded to Google and it is not shared with an exit node.
type CertificateAuthority struct {
	cert    *x509.Certificate
	certPEM []byte
	key     crypto.Signer

	mu     sync.Mutex
	leaves map[string]*tls.Certificate
}

// LoadOrCreateCA loads a persistent local CA, or creates one on first use.
//
// A corrupt existing pair is an error rather than a silent rotation: silently replacing the CA
// would leave the phone trusting the old certificate while the client presented leaves from the
// new one, which looks exactly like a random network failure.
func LoadOrCreateCA(certPath, keyPath string) (*CertificateAuthority, error) {
	if certPath == "" || keyPath == "" {
		return nil, errors.New("guts: fetch mode needs a CA certificate path and a CA key path")
	}
	certPEM, certErr := os.ReadFile(certPath)
	keyPEM, keyErr := os.ReadFile(keyPath)
	if certErr == nil && keyErr == nil {
		return parseCA(certPEM, keyPEM)
	}
	if (certErr == nil) != (keyErr == nil) {
		return nil, errors.New("guts: the local CA certificate and key must either both exist or both be created")
	}
	if !errors.Is(certErr, os.ErrNotExist) {
		return nil, certErr
	}
	if !errors.Is(keyErr, os.ErrNotExist) {
		return nil, keyErr
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := randomSerial()
	if err != nil {
		return nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "Omni GUTS Local CA",
			Organization: []string{"Omni"},
		},
		NotBefore:             now.Add(-24 * time.Hour),
		NotAfter:              now.AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	for _, path := range []string{certPath, keyPath} {
		if dir := filepath.Dir(path); dir != "." && dir != "" {
			if err := os.MkdirAll(dir, 0o700); err != nil {
				return nil, err
			}
		}
	}
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return nil, err
	}
	return parseCA(certPEM, keyPEM)
}

func parseCA(certPEM, keyPEM []byte) (*CertificateAuthority, error) {
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil || certBlock.Type != "CERTIFICATE" {
		return nil, errors.New("guts: the local CA certificate is not PEM")
	}
	cert, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return nil, fmt.Errorf("guts: parse local CA certificate: %w", err)
	}
	if !cert.IsCA {
		return nil, errors.New("guts: the local certificate is not a CA")
	}
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil {
		return nil, errors.New("guts: the local CA key is not PEM")
	}
	var parsed any
	parsed, err = x509.ParseECPrivateKey(keyBlock.Bytes)
	if err != nil {
		parsed, err = x509.ParsePKCS8PrivateKey(keyBlock.Bytes)
		if err != nil {
			return nil, fmt.Errorf("guts: parse local CA key: %w", err)
		}
	}
	signer, ok := parsed.(crypto.Signer)
	if !ok {
		return nil, errors.New("guts: the local CA key cannot sign certificates")
	}
	return &CertificateAuthority{
		cert:    cert,
		certPEM: append([]byte(nil), certPEM...),
		key:     signer,
		leaves:  map[string]*tls.Certificate{},
	}, nil
}

// CertificatePEM is the public root the user installs on this phone.
func (ca *CertificateAuthority) CertificatePEM() []byte {
	return append([]byte(nil), ca.certPEM...)
}

// FingerprintSHA256 is what the install screen can show so the user can compare it in Settings.
func (ca *CertificateAuthority) FingerprintSHA256() string {
	sum := sha256.Sum256(ca.cert.Raw)
	encoded := strings.ToUpper(hex.EncodeToString(sum[:]))
	parts := make([]string, 0, len(encoded)/2)
	for i := 0; i < len(encoded); i += 2 {
		parts = append(parts, encoded[i:i+2])
	}
	return strings.Join(parts, ":")
}

// TLSConfig terminates HTTPS for fallbackHost, while still honouring the browser's SNI when it
// sends one. HTTP/2 is deliberately not offered: fetch mode translates one HTTP/1.1 request at a
// time to Apps Script, and pretending to speak h2 would only hide that.
func (ca *CertificateAuthority) TLSConfig(fallbackHost string) *tls.Config {
	return &tls.Config{
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"http/1.1"},
		GetCertificate: func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
			host := hello.ServerName
			if host == "" {
				host = fallbackHost
			}
			return ca.certificateFor(host)
		},
	}
}

func (ca *CertificateAuthority) certificateFor(host string) (*tls.Certificate, error) {
	host = normalizeTLSHost(host)
	if host == "" {
		host = "localhost"
	}

	ca.mu.Lock()
	defer ca.mu.Unlock()
	if leaf := ca.leaves[host]; leaf != nil {
		return leaf, nil
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := randomSerial()
	if err != nil {
		return nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: host},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if ip := net.ParseIP(host); ip != nil {
		template.IPAddresses = []net.IP{ip}
	} else {
		template.DNSNames = []string{host}
	}
	der, err := x509.CreateCertificate(rand.Reader, template, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		return nil, err
	}
	leaf := &tls.Certificate{
		Certificate: [][]byte{der, ca.cert.Raw},
		PrivateKey:  key,
	}
	ca.leaves[host] = leaf
	return leaf, nil
}

func normalizeTLSHost(host string) string {
	host = strings.TrimSpace(strings.ToLower(host))
	host = strings.TrimSuffix(host, ".")
	if parsedHost, _, err := net.SplitHostPort(host); err == nil {
		host = parsedHost
	}
	return strings.Trim(host, "[]")
}

func randomSerial() (*big.Int, error) {
	limit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, limit)
	if err != nil {
		return nil, err
	}
	if serial.Sign() == 0 {
		serial.SetInt64(1)
	}
	return serial, nil
}
