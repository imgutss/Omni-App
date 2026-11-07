package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"noizdns/mobile"
)

// mixedTunnel runs one real mobile tunnel per resolver when a SlipNet profile
// contains different authoritative modes. The mobile library deliberately has
// pool-wide KCP/poll/cover-traffic settings, so putting unlike resolvers in one
// pool would silently discard their individual modes. A small local SOCKS5
// negotiator preserves those modes by giving each resolver its own tunnel.
// Fanout races the SOCKS greeting across all tunnels; round-robin races the
// configured spread. After negotiation only the winning tunnel carries the
// connection, so a remote SOCKS request is never duplicated.
type mixedTunnel struct {
	options   clientOptions
	clients   []*mobile.DnsttClient
	addresses []string

	listener net.Listener
	running  atomic.Bool
	stopOnce sync.Once
	wg       sync.WaitGroup
	rr       uint64

	activeMu sync.Mutex
	active   map[net.Conn]struct{}
}

const maxMixedResolvers = 32

func newMixedTunnel(options clientOptions, endpoints []string, flags []bool) (*mixedTunnel, error) {
	if len(endpoints) > maxMixedResolvers {
		return nil, fmt.Errorf("mixed tunnel supports at most %d resolvers", maxMixedResolvers)
	}
	// A raw SSH relay also uses this composite for one resolver or a
	// uniform pool: the underlying mobile listener is SOCKS5-only, while
	// the app-facing raw listener must preserve the SSH identification bytes.
	if options.rawTCP && len(flags) == 0 {
		flags = make([]bool, len(endpoints))
	}
	if len(endpoints) < 1 || len(endpoints) != len(flags) ||
		(!options.rawTCP && !hasMixedAuthority(flags)) {
		return nil, fmt.Errorf("composite tunnel requires matching resolver flags (and a mixed pool unless raw TCP is enabled)")
	}
	host, _, err := net.SplitHostPort(options.listenAddr)
	if err != nil {
		return nil, fmt.Errorf("local listen address: %w", err)
	}
	m := &mixedTunnel{
		options:   options,
		clients:   make([]*mobile.DnsttClient, 0, len(endpoints)),
		addresses: make([]string, 0, len(endpoints)),
		active:    make(map[net.Conn]struct{}),
	}
	for i, endpoint := range endpoints {
		port, err := freeTCPPort(host)
		if err != nil {
			return nil, fmt.Errorf("allocate resolver %d listener: %w", i, err)
		}
		address := net.JoinHostPort(host, strconv.Itoa(port))
		// Field 27 is SlipNet's explicit pool-wide override. When it is
		// clear, each resolver's own marker controls its independent tunnel.
		client, err := options.newClient(strings.TrimSpace(endpoint), address, options.authoritative || flags[i])
		if err != nil {
			return nil, fmt.Errorf("create resolver %d: %w", i, err)
		}
		m.clients = append(m.clients, client)
		m.addresses = append(m.addresses, address)
	}
	return m, nil
}

func freeTCPPort(host string) (int, error) {
	listener, err := net.Listen("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return 0, err
	}
	port, ok := listener.Addr().(*net.TCPAddr)
	if !ok {
		listener.Close()
		return 0, fmt.Errorf("listener did not return a TCP address")
	}
	value := port.Port
	if err := listener.Close(); err != nil {
		return 0, err
	}
	return value, nil
}

func (m *mixedTunnel) Start() error {
	for i, client := range m.clients {
		if err := client.Start(); err != nil {
			for _, started := range m.clients {
				started.Stop()
			}
			return fmt.Errorf("start resolver %d: %w", i, err)
		}
	}
	// Open the app-facing listener only after every child tunnel has started;
	// a readiness probe must not reach an empty composite.
	listener, err := net.Listen("tcp", m.options.listenAddr)
	if err != nil {
		for _, client := range m.clients {
			client.Stop()
		}
		return fmt.Errorf("mixed local listener: %w", err)
	}
	m.listener = listener
	m.running.Store(true)
	m.wg.Add(1)
	go m.acceptLoop()
	log.Printf("mixed resolver tunnel started with %d independently tuned resolvers", len(m.clients))
	return nil
}

func (m *mixedTunnel) acceptLoop() {
	defer m.wg.Done()
	for {
		connection, err := m.listener.Accept()
		if err != nil {
			if m.running.Load() {
				m.running.Store(false)
				log.Printf("mixed resolver listener stopped: %v", err)
			}
			return
		}
		if !m.track(connection) {
			connection.Close()
			return
		}
		m.wg.Add(1)
		go func() {
			defer m.wg.Done()
			defer m.untrack(connection)
			defer connection.Close()
			m.handle(connection)
		}()
	}
}

func (m *mixedTunnel) track(connection net.Conn) bool {
	m.activeMu.Lock()
	defer m.activeMu.Unlock()
	if !m.running.Load() {
		return false
	}
	m.active[connection] = struct{}{}
	return true
}

func (m *mixedTunnel) untrack(connection net.Conn) {
	m.activeMu.Lock()
	delete(m.active, connection)
	m.activeMu.Unlock()
}

func (m *mixedTunnel) handle(front net.Conn) {
	if m.options.rawTCP {
		m.handleRaw(front)
		return
	}
	greeting, err := readSocksGreeting(front)
	if err != nil {
		return
	}
	backends := m.selectBackends()
	backend, err := m.negotiate(front, greeting, backends)
	if err != nil {
		return
	}
	defer backend.Close()

	// The greeting has already been exchanged. Everything after it is an
	// ordinary byte stream, including the one CONNECT request and its reply.
	done := make(chan struct{}, 1)
	go func() {
		_, _ = io.Copy(backend, front)
		_ = backend.Close()
		done <- struct{}{}
	}()
	_, _ = io.Copy(front, backend)
	_ = front.Close()
	_ = backend.Close()
	<-done
}

// handleRaw selects one independently tuned resolver and relays the SSH byte
// stream unchanged. A DNSTT+SSH endpoint is not a SOCKS server: negotiating
// SOCKS here would consume the SSH identification and corrupt the handshake.
func (m *mixedTunnel) handleRaw(front net.Conn) {
	var backend net.Conn
	for _, index := range m.selectBackends() {
		connection, err := net.DialTimeout("tcp", m.addresses[index], 2*time.Second)
		if err == nil {
			backend = connection
			break
		}
	}
	if backend == nil {
		return
	}
	defer backend.Close()
	_ = front.SetDeadline(time.Time{})
	_ = backend.SetDeadline(time.Time{})
	done := make(chan struct{}, 1)
	go func() {
		_, _ = io.Copy(backend, front)
		_ = backend.Close()
		done <- struct{}{}
	}()
	_, _ = io.Copy(front, backend)
	_ = front.Close()
	_ = backend.Close()
	<-done
}

func readSocksGreeting(connection net.Conn) ([]byte, error) {
	_ = connection.SetReadDeadline(time.Now().Add(15 * time.Second))
	header := make([]byte, 2)
	if _, err := io.ReadFull(connection, header); err != nil {
		return nil, err
	}
	if header[0] != 5 || header[1] == 0 {
		return nil, fmt.Errorf("invalid SOCKS5 greeting")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(connection, methods); err != nil {
		return nil, err
	}
	return append(header, methods...), nil
}

type socksResult struct {
	connection net.Conn
	response   []byte
	err        error
}

func (m *mixedTunnel) negotiate(front net.Conn, greeting []byte, indices []int) (net.Conn, error) {
	connections := make([]net.Conn, 0, len(indices))
	for _, index := range indices {
		connection, err := net.DialTimeout("tcp", m.addresses[index], 2*time.Second)
		if err != nil {
			continue
		}
		_ = connection.SetDeadline(time.Now().Add(15 * time.Second))
		if _, err := connection.Write(greeting); err != nil {
			connection.Close()
			continue
		}
		connections = append(connections, connection)
	}
	if len(connections) == 0 {
		return nil, fmt.Errorf("no resolver tunnel accepted the local SOCKS connection")
	}

	results := make(chan socksResult, len(connections))
	for _, connection := range connections {
		go func(connection net.Conn) {
			response := make([]byte, 2)
			_, err := io.ReadFull(connection, response)
			results <- socksResult{connection: connection, response: response, err: err}
		}(connection)
	}

	var winner net.Conn
	for range connections {
		result := <-results
		if result.err == nil && len(result.response) == 2 &&
			result.response[0] == 5 && result.response[1] == 0 {
			winner = result.connection
			if _, err := front.Write(result.response); err != nil {
				winner.Close()
				winner = nil
				for _, connection := range connections {
					connection.Close()
				}
				return nil, err
			}
			// Do not wait for a dead resolver's timeout after a healthy
			// tunnel has already answered. Pending result sends are buffered.
			break
		}
	}
	for _, connection := range connections {
		if connection != winner {
			connection.Close()
		}
	}
	if winner == nil {
		return nil, fmt.Errorf("no resolver tunnel completed SOCKS5 negotiation")
	}
	_ = winner.SetDeadline(time.Time{})
	_ = front.SetDeadline(time.Time{})
	return winner, nil
}

func (m *mixedTunnel) selectBackends() []int {
	n := len(m.clients)
	indices := make([]int, 0, n)
	if !strings.EqualFold(m.options.resolverMode, "roundrobin") {
		for i := range m.clients {
			indices = append(indices, i)
		}
		return indices
	}
	count := m.options.rrSpread
	if count < 1 {
		count = 1
	}
	if count > n {
		count = n
	}
	start := int((atomic.AddUint64(&m.rr, 1) - 1) % uint64(n))
	for i := 0; i < count; i++ {
		indices = append(indices, (start+i)%n)
	}
	return indices
}

func (m *mixedTunnel) IsRunning() bool {
	if !m.running.Load() {
		return false
	}
	for _, client := range m.clients {
		if !client.IsRunning() {
			return false
		}
	}
	return true
}

func (m *mixedTunnel) Stop() {
	m.stopOnce.Do(func() {
		m.running.Store(false)
		if m.listener != nil {
			_ = m.listener.Close()
		}
		m.activeMu.Lock()
		for connection := range m.active {
			_ = connection.Close()
		}
		m.activeMu.Unlock()
		for _, client := range m.clients {
			client.Stop()
		}
	})
	m.wg.Wait()
}
