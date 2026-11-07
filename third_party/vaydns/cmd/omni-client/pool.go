package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/net2share/vaydns/turbotunnel"
)

type poolPacket struct {
	data []byte
	err  error
}

// resolverPool is a bounded packet multiplexer. Every child still performs
// the real DNS exchange; the pool only chooses where each query is sent and
// normalizes response addresses for KCP.
type resolverPool struct {
	children  []net.PacketConn
	targets   []net.Addr
	mode      string
	spread    int
	cursor    atomic.Uint64
	queue     chan poolPacket
	closed    chan struct{}
	readers   chan struct{}
	closeOnce sync.Once
	wg        sync.WaitGroup

	deadlineMu sync.RWMutex
	deadline   time.Time
}

func newResolverPool(children []net.PacketConn, mode string, spread int) (*resolverPool, error) {
	return newResolverPoolWithTargets(children, nil, mode, spread)
}

func newResolverPoolWithTargets(children []net.PacketConn, targets []net.Addr, mode string, spread int) (*resolverPool, error) {
	if len(children) == 0 || (mode != "fanout" && mode != "roundrobin") || spread < 1 ||
		(targets != nil && len(targets) != len(children)) {
		return nil, fmt.Errorf("invalid resolver pool")
	}
	resolvedTargets := make([]net.Addr, len(children))
	for i := range children {
		resolvedTargets[i] = turbotunnel.DummyAddr{}
		if targets != nil && targets[i] != nil {
			resolvedTargets[i] = targets[i]
		}
	}
	p := &resolverPool{
		children: children,
		targets:  resolvedTargets,
		mode:     mode,
		spread:   spread,
		queue:    make(chan poolPacket, 128),
		closed:   make(chan struct{}),
		readers:  make(chan struct{}),
	}
	for i, child := range children {
		p.wg.Add(1)
		go p.readChild(i, child)
	}
	go func() {
		p.wg.Wait()
		close(p.readers)
	}()
	return p, nil
}

func (p *resolverPool) readChild(index int, child net.PacketConn) {
	defer p.wg.Done()
	buffer := make([]byte, 65535)
	for {
		n, _, err := child.ReadFrom(buffer)
		if err != nil {
			select {
			case <-p.closed:
			case p.queue <- poolPacket{err: fmt.Errorf("resolver %d: %w", index, err)}:
			default:
			}
			return
		}
		data := make([]byte, n)
		copy(data, buffer[:n])
		select {
		case <-p.closed:
			return
		case p.queue <- poolPacket{data: data}:
		default:
			// A full receive queue drops this datagram rather than blocking every resolver.
		}
	}
}

func (p *resolverPool) ReadFrom(buffer []byte) (int, net.Addr, error) {
	for {
		p.deadlineMu.RLock()
		deadline := p.deadline
		p.deadlineMu.RUnlock()
		var timeout <-chan time.Time
		if !deadline.IsZero() {
			remaining := time.Until(deadline)
			if remaining <= 0 {
				return 0, nil, os.ErrDeadlineExceeded
			}
			timer := time.NewTimer(remaining)
			defer timer.Stop()
			timeout = timer.C
		}
		select {
		case <-p.closed:
			return 0, nil, net.ErrClosed
		case <-p.readers:
			return 0, nil, io.ErrClosedPipe
		case <-timeout:
			return 0, nil, os.ErrDeadlineExceeded
		case packet := <-p.queue:
			if packet.err != nil {
				log.Printf("resolver read ended: %v", packet.err)
				continue
			}
			if len(packet.data) > len(buffer) {
				return 0, nil, io.ErrShortBuffer
			}
			copy(buffer, packet.data)
			return len(packet.data), turbotunnel.DummyAddr{}, nil
		}
	}
}

func (p *resolverPool) WriteTo(buffer []byte, _ net.Addr) (int, error) {
	select {
	case <-p.closed:
		return 0, net.ErrClosed
	default:
	}
	if p.mode == "fanout" {
		var lastErr error
		succeeded := false
		for index, child := range p.children {
			n, err := child.WriteTo(buffer, p.targets[index])
			if err == nil && n == len(buffer) {
				succeeded = true
			} else if err != nil {
				lastErr = err
			}
		}
		if succeeded {
			return len(buffer), nil
		}
		if lastErr == nil {
			lastErr = io.ErrShortWrite
		}
		return 0, lastErr
	}

	start := int(p.cursor.Add(1)-1) % len(p.children)
	var lastErr error
	succeeded := false
	attempts := p.spread
	if attempts > len(p.children) {
		attempts = len(p.children)
	}
	for i := 0; i < attempts; i++ {
		index := (start + i) % len(p.children)
		child := p.children[index]
		n, err := child.WriteTo(buffer, p.targets[index])
		if err == nil && n == len(buffer) {
			succeeded = true
		} else if err != nil {
			lastErr = err
		}
	}
	if succeeded {
		return len(buffer), nil
	}
	if lastErr == nil {
		lastErr = errors.New("no round-robin resolver accepted the query")
	}
	return 0, lastErr
}

func (p *resolverPool) Close() error {
	p.closeOnce.Do(func() {
		close(p.closed)
		for _, child := range p.children {
			_ = child.Close()
		}
	})
	return nil
}

func (p *resolverPool) LocalAddr() net.Addr {
	if len(p.children) == 0 {
		return turbotunnel.DummyAddr{}
	}
	return p.children[0].LocalAddr()
}

func (p *resolverPool) SetDeadline(deadline time.Time) error {
	p.setReadDeadline(deadline)
	return p.forEach(func(child net.PacketConn) error { return child.SetDeadline(deadline) })
}

func (p *resolverPool) SetReadDeadline(deadline time.Time) error {
	p.setReadDeadline(deadline)
	return p.forEach(func(child net.PacketConn) error { return child.SetReadDeadline(deadline) })
}

func (p *resolverPool) SetWriteDeadline(deadline time.Time) error {
	return p.forEach(func(child net.PacketConn) error { return child.SetWriteDeadline(deadline) })
}

func (p *resolverPool) setReadDeadline(deadline time.Time) {
	p.deadlineMu.Lock()
	p.deadline = deadline
	p.deadlineMu.Unlock()
}

func (p *resolverPool) forEach(operation func(net.PacketConn) error) error {
	var first error
	for _, child := range p.children {
		if err := operation(child); err != nil && first == nil {
			first = err
		}
	}
	return first
}
