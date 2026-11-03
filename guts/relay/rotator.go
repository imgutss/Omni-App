package relay

import (
	"context"
	"sort"
	"sync"
	"time"

	"guts/proto"
)

// Rotator spreads calls over several doors: the fastest one that answered a ping is used first, and
// a door that fails is skipped until the next ping reorders them. Two Apps Script deployments, or a
// deployment and a direct exit node, therefore add up instead of one being a single point of failure.
type Rotator struct {
	mu    sync.Mutex
	doors []Door
}

// NewRotator builds a rotator over doors, in the given order.
func NewRotator(doors ...Door) *Rotator {
	return &Rotator{doors: doors}
}

// Describe names the doors this rotator holds.
func (r *Rotator) Describe() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	names := make([]string, 0, len(r.doors))
	for _, d := range r.doors {
		names = append(names, d.Describe())
	}
	return "rotator[" + join(names, ", ") + "]"
}

// Call tries the current order and moves a failing door to the back.
func (r *Rotator) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	r.mu.Lock()
	doors := append([]Door(nil), r.doors...)
	r.mu.Unlock()

	var lastErr error
	for _, d := range doors {
		resp, err := d.Call(ctx, req)
		if err == nil {
			r.promote(d)
			return resp, nil
		}
		lastErr = err
		r.demote(d)
	}
	if lastErr == nil {
		lastErr = errNoDoor
	}
	return proto.Response{}, lastErr
}

// Pick measures every door with a ping and keeps the fastest first.
func (r *Rotator) Pick(ctx context.Context) map[string]time.Duration {
	r.mu.Lock()
	doors := append([]Door(nil), r.doors...)
	r.mu.Unlock()

	type timed struct {
		d Door
		t time.Duration
	}
	results := make([]timed, 0, len(doors))
	latencies := map[string]time.Duration{}
	for _, d := range doors {
		probeCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
		started := time.Now()
		_, err := d.Call(probeCtx, proto.Request{Op: proto.OpPing})
		cancel()
		if err != nil {
			latencies[d.Describe()] = -1
			continue
		}
		took := time.Since(started)
		latencies[d.Describe()] = took
		results = append(results, timed{d: d, t: took})
	}
	sort.SliceStable(results, func(i, j int) bool { return results[i].t < results[j].t })

	r.mu.Lock()
	failed := make([]Door, 0, len(doors))
	for _, d := range doors {
		if latencies[d.Describe()] >= 0 {
			continue
		}
		failed = append(failed, d)
	}
	ordered := make([]Door, 0, len(doors))
	for _, t := range results {
		ordered = append(ordered, t.d)
	}
	r.doors = append(ordered, failed...)
	r.mu.Unlock()
	return latencies
}

func (r *Rotator) promote(d Door) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i, existing := range r.doors {
		if existing == d {
			if i == 0 {
				return
			}
			copy(r.doors[1:i+1], r.doors[:i])
			r.doors[0] = d
			return
		}
	}
}

func (r *Rotator) demote(d Door) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i, existing := range r.doors {
		if existing == d && i < len(r.doors)-1 {
			copy(r.doors[i:], r.doors[i+1:])
			r.doors[len(r.doors)-1] = d
			return
		}
	}
}

func join(items []string, sep string) string {
	out := ""
	for i, item := range items {
		if i > 0 {
			out += sep
		}
		out += item
	}
	return out
}

var errNoDoor = errNoDoorType("guts: no door answered")

type errNoDoorType string

func (e errNoDoorType) Error() string { return string(e) }
