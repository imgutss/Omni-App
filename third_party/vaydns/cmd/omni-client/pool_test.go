package main

import (
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type memoryPacketConn struct {
	mu     sync.Mutex
	writes [][]byte
	addrs  []net.Addr
	reads  chan []byte
	closed chan struct{}
}

func newMemoryPacketConn() *memoryPacketConn {
	return &memoryPacketConn{reads: make(chan []byte, 8), closed: make(chan struct{})}
}

func (c *memoryPacketConn) ReadFrom(buffer []byte) (int, net.Addr, error) {
	select {
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case data := <-c.reads:
		return copy(buffer, data), nil, nil
	}
}

func (c *memoryPacketConn) WriteTo(data []byte, addr net.Addr) (int, error) {
	c.mu.Lock()
	c.writes = append(c.writes, append([]byte(nil), data...))
	c.addrs = append(c.addrs, addr)
	c.mu.Unlock()
	return len(data), nil
}

func (c *memoryPacketConn) Close() error {
	select {
	case <-c.closed:
	default:
		close(c.closed)
	}
	return nil
}
func (c *memoryPacketConn) LocalAddr() net.Addr              { return nil }
func (c *memoryPacketConn) SetDeadline(time.Time) error      { return nil }
func (c *memoryPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c *memoryPacketConn) SetWriteDeadline(time.Time) error { return nil }

func TestResolverPoolFanoutAndRoundRobin(t *testing.T) {
	first, second := newMemoryPacketConn(), newMemoryPacketConn()
	pool, err := newResolverPool([]net.PacketConn{first, second}, "fanout", 3)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if _, err := pool.WriteTo([]byte("query"), nil); err != nil {
		t.Fatal(err)
	}
	first.mu.Lock()
	firstWrites := len(first.writes)
	first.mu.Unlock()
	second.mu.Lock()
	secondWrites := len(second.writes)
	second.mu.Unlock()
	if firstWrites != 1 || secondWrites != 1 {
		t.Fatalf("fanout writes = %d, %d", firstWrites, secondWrites)
	}

	second.reads <- []byte("answer")
	buffer := make([]byte, 32)
	n, _, err := pool.ReadFrom(buffer)
	if err != nil || string(buffer[:n]) != "answer" {
		t.Fatalf("read = %q, %v", buffer[:n], err)
	}

	rr, err := newResolverPool([]net.PacketConn{first, second}, "roundrobin", 2)
	if err != nil {
		t.Fatal(err)
	}
	defer rr.Close()
	if _, err := rr.WriteTo([]byte("one"), nil); err != nil {
		t.Fatal(err)
	}
	first.mu.Lock()
	second.mu.Lock()
	if len(first.writes) != 2 || len(second.writes) != 2 {
		t.Fatalf("round-robin spread did not reach both resolvers")
	}
	first.mu.Unlock()
	second.mu.Unlock()
}

func TestResolverPoolPreservesPerResolverWriteTargets(t *testing.T) {
	child := newMemoryPacketConn()
	target := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 5300}
	pool, err := newResolverPoolWithTargets([]net.PacketConn{child}, []net.Addr{target}, "fanout", 1)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if _, err := pool.WriteTo([]byte("query"), nil); err != nil {
		t.Fatal(err)
	}
	child.mu.Lock()
	defer child.mu.Unlock()
	if len(child.addrs) != 1 || child.addrs[0] != target {
		t.Fatalf("resolver target was not preserved: %#v", child.addrs)
	}
}

func TestResolverPoolReportsClosedTransport(t *testing.T) {
	child := newMemoryPacketConn()
	pool, err := newResolverPool([]net.PacketConn{child}, "fanout", 1)
	if err != nil {
		t.Fatal(err)
	}
	_ = child.Close()
	_ = pool.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = pool.ReadFrom(make([]byte, 8))
	if err == nil || err == io.EOF {
		t.Fatalf("expected a real transport close error, got %v", err)
	}
}
