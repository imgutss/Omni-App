package relay

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"guts/proto"
)

// A door is a Google Apps Script web app, and an Apps Script project has a daily call quota per
// account. Several doors — one per Google account — add up to that much more traffic, but only if
// the calls are spread over them: a client that always uses its fastest door burns one account's
// day while the others sit idle, and then fails at the worst moment.
//
// Quota is that bookkeeping and nothing else: calls per door per day, an optional persisted file so
// a restart does not forget the morning, and an honest refusal once a door's day is spent.

// ErrQuotaSpent says every door has used its day.
var ErrQuotaSpent = errors.New("guts: every door has spent its daily call budget")

// Quota counts the door calls of one day.
type Quota struct {
	mu     sync.Mutex
	budget int // per door per day; 0 means unlimited
	day    string
	used   map[string]int
	path   string // where to persist, empty to keep it in memory
	now    func() time.Time
}

// NewQuota builds a counter with a per-door daily budget, keeping state in path when path is not
// empty.
func NewQuota(budget int, path string) *Quota {
	q := &Quota{budget: budget, used: map[string]int{}, path: path, now: time.Now}
	// The day is set before loading, and not through roll: roll writes the counters it just
	// emptied, which would erase this morning's file before it was read.
	q.day = q.now().Format("2006-01-02")
	q.load()
	return q
}

// Budget reports the daily budget per door; zero means unlimited.
func (q *Quota) Budget() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.budget
}

// Remaining is what a door may still spend today; -1 means unlimited.
func (q *Quota) Remaining(door string) int {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.roll()
	if q.budget <= 0 {
		return -1
	}
	left := q.budget - q.used[door]
	if left < 0 {
		return 0
	}
	return left
}

// Spend records one call on a door, and reports whether it was allowed. A door over budget is
// refused here rather than at the door, so the quota is never overspent by rounding.
func (q *Quota) Spend(door string) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.roll()
	if q.budget > 0 && q.used[door] >= q.budget {
		return false
	}
	q.used[door]++
	q.saveLocked()
	return true
}

// Exhausted reports whether every door of a set has spent its day.
func (q *Quota) Exhausted(doors []string) bool {
	if q.Budget() <= 0 {
		return false
	}
	for _, door := range doors {
		if q.Remaining(door) > 0 {
			return false
		}
	}
	return true
}

// Close persists the counters now, which a client does on the way out.
func (q *Quota) Close() { q.save() }

// save persists the counters now.
func (q *Quota) save() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.roll()
	q.saveLocked()
}

// Snapshot is today's usage per door, for logs and for the app to show.
func (q *Quota) Snapshot() map[string]int {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.roll()
	out := make(map[string]int, len(q.used))
	for door, used := range q.used {
		out[door] = used
	}
	return out
}

// Day is the day the counters belong to.
func (q *Quota) Day() string {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.roll()
	return q.day
}

// roll starts a new day when the clock has moved past midnight since the counters were written.
// The day is the phone's day, not UTC's: a quota resets with the account's own midnight, and the
// difference is an hour or two of confusion nobody needs.
func (q *Quota) roll() {
	today := q.now().Format("2006-01-02")
	if q.day == today {
		return
	}
	q.day = today
	q.used = map[string]int{}
	q.saveLocked()
}

type quotaFile struct {
	Day  string         `json:"day"`
	Used map[string]int `json:"used"`
}

// load reads yesterday's file, keeping only the counters of today.
func (q *Quota) load() {
	if q.path == "" {
		return
	}
	raw, err := os.ReadFile(q.path)
	if err != nil {
		return
	}
	var saved quotaFile
	if err := json.Unmarshal(raw, &saved); err != nil {
		return
	}
	if saved.Day != q.day {
		return
	}
	for door, used := range saved.Used {
		q.used[door] = used
	}
}

// saveLocked writes the counters; a failure to write is not worth failing a tunnel over, so it is
// simply skipped — the worst case is that a restart forgets part of the day.
func (q *Quota) saveLocked() {
	if q.path == "" {
		return
	}
	raw, err := json.Marshal(quotaFile{Day: q.day, Used: q.used})
	if err != nil {
		return
	}
	if dir := filepath.Dir(q.path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return
		}
	}
	tmp := q.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, q.path)
}

// QuotaRotator is a rotator that also spreads calls to keep every account's day alive.
//
// It keeps the rotator's health ordering — a door that just failed goes to the back — and then, of
// the doors that are healthy, picks the one with the most of its day left. The fastest door
// therefore still wins when everyone is fresh, and a client with one busy account spreads the rest
// over the others instead of dying with it.
type QuotaRotator struct {
	rotator *Rotator
	quota   *Quota
}

// NewQuotaRotator wraps a rotator with a quota.
func NewQuotaRotator(rotator *Rotator, quota *Quota) *QuotaRotator {
	return &QuotaRotator{rotator: rotator, quota: quota}
}

// Quota exposes the counters, for logs and for the app.
func (q *QuotaRotator) Quota() *Quota { return q.quota }

// Describe names the doors, with how much of their day is left.
func (q *QuotaRotator) Describe() string { return q.rotator.Describe() }

// Call spends one call on the least-spent healthy door.
func (q *QuotaRotator) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	doors := q.rotator.order()
	// Health order first, then the door with the most of its day left: while every account is fresh
	// this changes nothing, and once one is used up the calls move to the others instead of dying
	// with it.
	sort.SliceStable(doors, func(i, j int) bool {
		return q.quota.Remaining(doors[i].Describe()) > q.quota.Remaining(doors[j].Describe())
	})
	var lastErr error
	for _, d := range doors {
		name := d.Describe()
		if !q.quota.Spend(name) {
			lastErr = ErrQuotaSpent
			continue
		}
		resp, err := d.Call(ctx, req)
		if err == nil {
			q.rotator.promote(d)
			return resp, nil
		}
		lastErr = err
		q.rotator.demote(d)
	}
	if lastErr == nil {
		lastErr = errNoDoor
	}
	return proto.Response{}, lastErr
}

// Close persists the counters.
func (q *QuotaRotator) Close() { q.quota.save() }
