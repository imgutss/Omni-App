package relay_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"guts/proto"
	"guts/relay"
)

type fakeDoor struct {
	name  string
	fails bool
	calls int
	mu    sync.Mutex
}

func (f *fakeDoor) Describe() string { return f.name }

func (f *fakeDoor) Call(_ context.Context, req proto.Request) (proto.Response, error) {
	f.mu.Lock()
	f.calls++
	fails := f.fails
	f.mu.Unlock()
	if fails {
		return proto.Response{}, errors.New("down")
	}
	return proto.Response{OK: true, Info: f.name}, nil
}

func (f *fakeDoor) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func TestRotatorFallsBackToTheNextDoor(t *testing.T) {
	broken := &fakeDoor{name: "broken", fails: true}
	healthy := &fakeDoor{name: "healthy"}
	r := relay.NewRotator(broken, healthy)

	resp, err := r.Call(context.Background(), proto.Request{Op: proto.OpPing})
	if err != nil {
		t.Fatalf("call: %v", err)
	}
	if resp.Info != "healthy" {
		t.Fatalf("answered by %q", resp.Info)
	}
	// The healthy door is now first, so the next call does not touch the broken one again.
	before := broken.count()
	_, _ = r.Call(context.Background(), proto.Request{Op: proto.OpPing})
	if broken.count() != before {
		t.Fatal("the broken door was tried again after being demoted")
	}
}

func TestRotatorPickOrdersByLatency(t *testing.T) {
	slow := &fakeDoor{name: "slow"}
	fast := &fakeDoor{name: "fast"}
	r := relay.NewRotator(slow, fast)

	// Give the fake doors a difference the pick can see: a ping that takes longer.
	slowPing := &delayedDoor{delay: 40 * time.Millisecond}
	fastPing := &delayedDoor{delay: 1 * time.Millisecond}
	r = relay.NewRotator(slowPing, fastPing)
	latencies := r.Pick(context.Background())
	if latencies[slowPing.Describe()] <= latencies[fastPing.Describe()] {
		t.Fatalf("latencies are not ordered: %v", latencies)
	}
	resp, err := r.Call(context.Background(), proto.Request{Op: proto.OpPing})
	if err != nil {
		t.Fatalf("call: %v", err)
	}
	if resp.Info != fastPing.Describe() {
		t.Fatalf("the faster door is not first: %q", resp.Info)
	}
}

func TestRotatorReportsWhenEveryDoorFails(t *testing.T) {
	a := &fakeDoor{name: "a", fails: true}
	b := &fakeDoor{name: "b", fails: true}
	r := relay.NewRotator(a, b)
	if _, err := r.Call(context.Background(), proto.Request{Op: proto.OpPing}); err == nil {
		t.Fatal("two broken doors answered")
	}
}

type delayedDoor struct{ delay time.Duration }

func (d *delayedDoor) Describe() string { return "delayed-" + d.delay.String() }

func (d *delayedDoor) Call(ctx context.Context, _ proto.Request) (proto.Response, error) {
	time.Sleep(d.delay)
	return proto.Response{OK: true, Info: d.Describe()}, nil
}
