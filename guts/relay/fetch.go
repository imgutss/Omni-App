package relay

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"guts/proto"
)

// UseFetchMITM switches this proxy from raw-TCP exit mode to certificate/fetch mode.
//
// HTTPS is terminated with the user's own local CA, translated into one HTTP request, and sent to
// the Apps Script door. The door retrieves the page itself, so this mode needs no exit node and
// no /g endpoint. Plain HTTP needs no certificate and follows the same fetch path. Google-owned
// HTTPS destinations are the one exception when UseGoogleDirect is configured: YouTube rejects
// Apps Script's server-side fetches, so those requests go straight to Google's edge instead.
func (p *Proxy) UseFetchMITM(ca *CertificateAuthority) {
	p.mu.Lock()
	p.fetchCA = ca
	p.mu.Unlock()
}

// CheckFetch is the fetch-mode door check. A legacy exit door also answers a ping, so a ping
// alone would give a green result for a door that will refuse every actual web request.
func CheckFetch(ctx context.Context, door Door) (string, error) {
	info, err := Check(ctx, door)
	if err != nil {
		return "", err
	}
	if !strings.Contains(strings.ToLower(info), proto.FetchCapability) {
		return info, fmt.Errorf("guts: door answered %q but does not advertise fetch; deploy the certificate/fetch Apps Script code", info)
	}
	return info, nil
}

func (p *Proxy) handleFetch(c net.Conn, ctx context.Context, host string, port int, ca *CertificateAuthority) {
	if port != 80 && port != 443 {
		p.log("fetch: %s:%d refused: certificate mode carries HTTP and HTTPS web requests, not raw TCP", host, port)
		_, _ = c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})

	scheme := "http"
	serveConn := c
	tlsHost := host
	if port == 443 {
		scheme = "https"
		tlsConn := tls.Server(c, ca.TLSConfig(host))
		_ = tlsConn.SetDeadline(time.Now().Add(30 * time.Second))
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			p.log("fetch: TLS for %s:%d: %v (the local CA may not be installed or trusted)", host, port, err)
			_ = tlsConn.Close()
			return
		}
		_ = tlsConn.SetDeadline(time.Time{})
		if state := tlsConn.ConnectionState(); state.ServerName != "" {
			tlsHost = state.ServerName
		}
		serveConn = tlsConn
		defer func() { _ = tlsConn.Close() }()
	}

	p.serveFetchHTTP(ctx, serveConn, scheme, tlsHost, host, port)
}

func (p *Proxy) serveFetchHTTP(ctx context.Context, c net.Conn, scheme, tlsHost, socksHost string, socksPort int) {
	reader := bufio.NewReaderSize(c, 64<<10)
	writer := bufio.NewWriterSize(c, 64<<10)
	defer func() { _ = writer.Flush() }()

	for {
		_ = c.SetReadDeadline(time.Now().Add(2 * time.Minute))
		req, err := http.ReadRequest(reader)
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
				p.log("fetch: read %s request: %v", scheme, err)
			}
			return
		}
		_ = c.SetReadDeadline(time.Time{})

		if strings.EqualFold(req.Method, http.MethodConnect) {
			writeFetchError(writer, req, http.StatusMethodNotAllowed, "CONNECT is not carried by Apps Script fetch mode", true)
			return
		}
		if headerHasToken(req.Header, "Upgrade", "websocket") || req.Header.Get("Upgrade") != "" {
			writeFetchError(writer, req, http.StatusNotImplemented, "WebSocket and protocol upgrades are not carried by Apps Script fetch mode", true)
			return
		}
		if strings.EqualFold(req.Header.Get("Expect"), "100-continue") {
			if _, err := writer.WriteString("HTTP/1.1 100 Continue\r\n\r\n"); err != nil {
				return
			}
			if err := writer.Flush(); err != nil {
				return
			}
		}

		body, err := io.ReadAll(io.LimitReader(req.Body, proto.MaxFetchBodyBytes+1))
		_ = req.Body.Close()
		if err != nil {
			p.log("fetch: read %s %s body: %v", req.Method, requestHost(req, tlsHost, socksHost), err)
			return
		}
		if len(body) > proto.MaxFetchBodyBytes {
			writeFetchError(writer, req, http.StatusRequestEntityTooLarge, "The request body is too large for one Apps Script fetch", true)
			return
		}

		// UrlFetchApp accepts get/delete/patch/post/put, but not OPTIONS. A CORS preflight is a
		// browser-side permission question at the TLS endpoint we already terminate, so answer it
		// here instead of letting every script-driven site fail before its real request.
		if strings.EqualFold(req.Method, http.MethodOptions) {
			if err := writeFetchPreflight(writer, req); err != nil || req.Close {
				return
			}
			continue
		}

		target, err := fetchTargetURL(req, scheme, tlsHost, socksHost, socksPort)
		if err != nil {
			writeFetchError(writer, req, http.StatusBadRequest, err.Error(), true)
			return
		}

		// Google's own services are the one class of destination Apps Script should not fetch:
		// YouTube sees UrlFetchApp's server-side request as automated traffic and answers with
		// a sorry page, and video segments are long-lived Range traffic, not one small JSON
		// answer. Those hosts live on the same Google frontend the door already uses, so send
		// the browser's real request there directly and stream the answer back.
		if scheme == "https" {
			if direct := p.googleFetcher(); direct != nil && isGoogleDirectHost(targetHost(target)) {
				_ = c.SetWriteDeadline(time.Now().Add(15 * time.Minute))
				err := p.serveGoogleDirect(ctx, writer, req, target, body, direct)
				_ = c.SetWriteDeadline(time.Time{})
				if err != nil || req.Close {
					return
				}
				continue
			}
		}

		fetchCtx, cancel := context.WithTimeout(ctx, 90*time.Second)
		resp, err := p.door.Call(fetchCtx, proto.Request{
			Op:      proto.OpFetch,
			Method:  req.Method,
			URL:     target,
			Headers: sanitizeFetchRequestHeaders(req.Header),
			D:       base64.StdEncoding.EncodeToString(body),
		})
		cancel()
		if err != nil {
			p.log("fetch: %s %s: %v", req.Method, targetHost(target), err)
			writeFetchError(writer, req, http.StatusBadGateway, "The Apps Script door did not answer", true)
			return
		}
		if !resp.OK {
			msg := resp.Error
			if msg == "" {
				msg = "The Apps Script door refused the fetch"
			}
			p.log("fetch: %s %s: %s", req.Method, targetHost(target), msg)
			writeFetchError(writer, req, http.StatusBadGateway, msg, true)
			return
		}

		responseBody, err := base64.StdEncoding.DecodeString(resp.D)
		if err != nil {
			p.log("fetch: decode %s answer: %v", targetHost(target), err)
			writeFetchError(writer, req, http.StatusBadGateway, "The Apps Script door returned a broken response", true)
			return
		}
		if len(responseBody) > proto.MaxFetchBodyBytes {
			writeFetchError(writer, req, http.StatusBadGateway, "The website returned more data than one Apps Script fetch may carry; use a range-capable download", true)
			return
		}

		_ = c.SetWriteDeadline(time.Now().Add(2 * time.Minute))
		if err := writeFetchResponse(writer, req, resp, responseBody); err != nil {
			return
		}
		_ = c.SetWriteDeadline(time.Time{})
		if req.Close {
			return
		}
	}
}

func (p *Proxy) serveGoogleDirect(
	ctx context.Context,
	w *bufio.Writer,
	req *http.Request,
	target string,
	body []byte,
	direct GoogleDirectDoer,
) error {
	parsed, err := url.Parse(target)
	if err != nil || parsed.Host == "" {
		writeFetchError(w, req, http.StatusBadRequest, "the HTTP request target is not a usable URL", true)
		return nil
	}
	upstream, err := http.NewRequestWithContext(ctx, req.Method, target, bytes.NewReader(body))
	if err != nil {
		writeFetchError(w, req, http.StatusBadRequest, err.Error(), true)
		return nil
	}
	upstream.Header = sanitizeDirectRequestHeaders(req.Header)
	upstream.Host = parsed.Host
	upstream.ContentLength = int64(len(body))

	resp, err := direct.Do(upstream)
	if err != nil {
		p.log("google-direct: %s %s: %v", req.Method, parsed.Host, err)
		writeFetchError(w, req, http.StatusBadGateway, "The Google edge did not answer this Google request", true)
		return nil
	}
	defer func() { _ = resp.Body.Close() }()

	headers := sanitizeDirectResponseHeaders(resp.Header)
	applyFetchCORS(req, headers)
	contentLength := resp.ContentLength
	responseBody := resp.Body
	if strings.EqualFold(req.Method, http.MethodHead) || resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusNotModified {
		if resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusNotModified {
			contentLength = 0
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		responseBody = http.NoBody
	}
	status := resp.Status
	if status == "" {
		status = fmt.Sprintf("%d %s", resp.StatusCode, http.StatusText(resp.StatusCode))
	}
	out := &http.Response{
		Status:           status,
		StatusCode:       resp.StatusCode,
		Proto:            "HTTP/1.1",
		ProtoMajor:       1,
		ProtoMinor:       1,
		Header:           headers,
		Body:             responseBody,
		ContentLength:    contentLength,
		TransferEncoding: resp.TransferEncoding,
		Request:          req,
		Close:            req.Close || resp.Close,
	}
	if err := out.Write(w); err != nil {
		return err
	}
	return w.Flush()
}

func fetchTargetURL(req *http.Request, scheme, tlsHost, socksHost string, socksPort int) (string, error) {
	if req.URL != nil && req.URL.IsAbs() && req.URL.Host != "" {
		u := *req.URL
		if u.Scheme == "" {
			u.Scheme = scheme
		}
		return u.String(), nil
	}
	host := requestHost(req, tlsHost, socksHost)
	if host == "" {
		return "", errors.New("the HTTP request has no destination host")
	}
	if _, _, err := net.SplitHostPort(host); err != nil {
		defaultPort := (scheme == "http" && socksPort == 80) || (scheme == "https" && socksPort == 443)
		if !defaultPort {
			host = net.JoinHostPort(strings.Trim(host, "[]"), strconv.Itoa(socksPort))
		}
	}
	path := "/"
	if req.URL != nil {
		path = req.URL.RequestURI()
		if path == "*" || path == "" {
			path = "/"
		}
	}
	raw := scheme + "://" + host + path
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", fmt.Errorf("the HTTP request target is not a usable URL")
	}
	return parsed.String(), nil
}

func requestHost(req *http.Request, tlsHost, socksHost string) string {
	if req != nil && strings.TrimSpace(req.Host) != "" {
		return strings.TrimSpace(req.Host)
	}
	if strings.TrimSpace(tlsHost) != "" {
		return strings.TrimSpace(tlsHost)
	}
	return strings.TrimSpace(socksHost)
}

func sanitizeFetchRequestHeaders(in http.Header) map[string][]string {
	remove := hopByHopHeaderNames(in)
	// Apps Script rejects some browser control headers, and forwarded-address headers would tell
	// the destination more about the phone than a web request needs to say.
	for _, name := range []string{
		"Priority", "Forwarded", "Via", "X-Forwarded-For", "X-Forwarded-Host",
		"X-Forwarded-Port", "X-Forwarded-Proto", "X-Real-IP", "CF-Connecting-IP",
		"True-Client-IP", "Fastly-Client-IP",
	} {
		remove[http.CanonicalHeaderKey(name)] = true
	}
	out := map[string][]string{}
	for name, values := range in {
		canonical := http.CanonicalHeaderKey(name)
		if remove[canonical] || canonical == "Content-Length" || canonical == "Accept-Encoding" {
			continue
		}
		out[canonical] = append([]string(nil), values...)
	}
	// Apps Script returns the retrieved bytes inside JSON. Asking the origin for identity avoids a
	// second, inconsistent compression layer between UrlFetchApp and the browser.
	out["Accept-Encoding"] = []string{"identity"}
	return out
}

func sanitizeDirectRequestHeaders(in http.Header) http.Header {
	remove := hopByHopHeaderNames(in)
	for _, name := range []string{
		"Forwarded", "Via", "X-Forwarded-For", "X-Forwarded-Host",
		"X-Forwarded-Port", "X-Forwarded-Proto", "X-Real-IP", "CF-Connecting-IP",
		"True-Client-IP", "Fastly-Client-IP",
	} {
		remove[http.CanonicalHeaderKey(name)] = true
	}
	out := http.Header{}
	for name, values := range in {
		canonical := http.CanonicalHeaderKey(name)
		if remove[canonical] || canonical == "Content-Length" {
			continue
		}
		// Unlike Apps Script, the browser's Accept-Encoding stays intact here: Google's
		// compressed response is streamed back to the browser that asked for it.
		out[canonical] = append([]string(nil), values...)
	}
	return out
}

func sanitizeDirectResponseHeaders(in http.Header) http.Header {
	remove := hopByHopHeaderNames(in)
	out := http.Header{}
	for name, values := range in {
		canonical := http.CanonicalHeaderKey(name)
		if remove[canonical] || canonical == "Content-Length" {
			continue
		}
		out[canonical] = append([]string(nil), values...)
	}
	return out
}

func sanitizeFetchResponseHeaders(in map[string][]string) http.Header {
	raw := http.Header{}
	for name, values := range in {
		raw[http.CanonicalHeaderKey(name)] = append([]string(nil), values...)
	}
	remove := hopByHopHeaderNames(raw)
	out := http.Header{}
	for name, values := range raw {
		if remove[name] || name == "Content-Length" {
			continue
		}
		out[name] = append([]string(nil), values...)
	}
	return out
}

func hopByHopHeaderNames(in http.Header) map[string]bool {
	remove := map[string]bool{
		"Connection":          true,
		"Keep-Alive":          true,
		"Proxy-Authenticate":  true,
		"Proxy-Authorization": true,
		"Proxy-Connection":    true,
		"Te":                  true,
		"Trailer":             true,
		"Transfer-Encoding":   true,
		"Upgrade":             true,
	}
	for _, value := range in.Values("Connection") {
		for _, token := range strings.Split(value, ",") {
			if token = strings.TrimSpace(token); token != "" {
				remove[http.CanonicalHeaderKey(token)] = true
			}
		}
	}
	return remove
}

func headerHasToken(header http.Header, name, token string) bool {
	for _, value := range header.Values(name) {
		for _, part := range strings.Split(value, ",") {
			if strings.EqualFold(strings.TrimSpace(part), token) {
				return true
			}
		}
	}
	return false
}

func writeFetchPreflight(w *bufio.Writer, req *http.Request) error {
	origin := strings.TrimSpace(req.Header.Get("Origin"))
	if origin == "" {
		origin = "*"
	}
	methods := strings.TrimSpace(req.Header.Get("Access-Control-Request-Method"))
	if methods == "" {
		methods = "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD"
	}
	requestHeaders := strings.TrimSpace(req.Header.Get("Access-Control-Request-Headers"))
	if requestHeaders == "" {
		requestHeaders = "*"
	}
	resp := &http.Response{
		Status:     "204 No Content",
		StatusCode: http.StatusNoContent,
		Proto:      "HTTP/1.1",
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header: http.Header{
			"Access-Control-Allow-Origin":      []string{origin},
			"Access-Control-Allow-Methods":     []string{methods},
			"Access-Control-Allow-Headers":     []string{requestHeaders},
			"Access-Control-Allow-Credentials": []string{"true"},
			"Access-Control-Max-Age":           []string{"86400"},
			"Vary":                             []string{"Origin, Access-Control-Request-Method, Access-Control-Request-Headers"},
		},
		Body:          io.NopCloser(bytes.NewReader(nil)),
		ContentLength: 0,
		Request:       req,
		Close:         req.Close,
	}
	if err := resp.Write(w); err != nil {
		return err
	}
	return w.Flush()
}

func applyFetchCORS(req *http.Request, headers http.Header) {
	origin := strings.TrimSpace(req.Header.Get("Origin"))
	if origin == "" {
		return
	}
	// Neither UrlFetchApp nor every Google API reliably returns a CORS answer a credentialed
	// browser request may use. We are the browser's TLS endpoint in this mode, so make the
	// browser-side permission explicit for its own origin.
	for name := range headers {
		if strings.HasPrefix(strings.ToLower(name), "access-control-") {
			delete(headers, name)
		}
	}
	headers.Set("Access-Control-Allow-Origin", origin)
	headers.Set("Access-Control-Allow-Credentials", "true")
	headers.Set("Access-Control-Expose-Headers", "*")
	headers.Add("Vary", "Origin")
}

func writeFetchResponse(w *bufio.Writer, req *http.Request, fetched proto.Response, body []byte) error {
	status := fetched.Status
	if status < 100 || status > 999 {
		status = http.StatusBadGateway
	}
	headers := sanitizeFetchResponseHeaders(fetched.Headers)
	applyFetchCORS(req, headers)
	contentLength := int64(len(body))
	if strings.EqualFold(req.Method, http.MethodHead) {
		if raw := firstHeaderValue(fetched.Headers, "Content-Length"); raw != "" {
			if parsed, err := strconv.ParseInt(raw, 10, 64); err == nil && parsed >= 0 {
				contentLength = parsed
			}
		}
		body = nil
	}
	if status == http.StatusNoContent || status == http.StatusNotModified {
		body = nil
		contentLength = 0
	}
	resp := &http.Response{
		Status:        fmt.Sprintf("%d %s", status, http.StatusText(status)),
		StatusCode:    status,
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        headers,
		Body:          io.NopCloser(bytes.NewReader(body)),
		ContentLength: contentLength,
		Request:       req,
		Close:         req.Close,
	}
	if err := resp.Write(w); err != nil {
		return err
	}
	return w.Flush()
}

func writeFetchError(w *bufio.Writer, req *http.Request, status int, message string, closeConn bool) {
	if message == "" {
		message = http.StatusText(status)
	}
	body := []byte(message + "\n")
	resp := &http.Response{
		Status:        fmt.Sprintf("%d %s", status, http.StatusText(status)),
		StatusCode:    status,
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        http.Header{"Content-Type": []string{"text/plain; charset=utf-8"}},
		Body:          io.NopCloser(bytes.NewReader(body)),
		ContentLength: int64(len(body)),
		Request:       req,
		Close:         closeConn,
	}
	_ = resp.Write(w)
	_ = w.Flush()
}

func firstHeaderValue(headers map[string][]string, wanted string) string {
	for name, values := range headers {
		if strings.EqualFold(name, wanted) && len(values) > 0 {
			return values[0]
		}
	}
	return ""
}

func targetHost(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "unknown host"
	}
	return parsed.Host
}
