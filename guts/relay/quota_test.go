package relay_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"guts/proto"
	"guts/relay"
)

// countingDoor is a door that answers every call and remembers how many it took.
type countingDoor struct {
	name string
	mu   sync.Mutex
	n    int
	err  error
}

func (d *countingDoor) Describe() string { return d.name }

func (d *countingDoor) Call(ctx context.Context, req proto.Request) (proto.Response, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.err != nil {
		return proto.Response{}, d.err
	}
	d.n++
	return proto.Response{OK: true}, nil
}

func (d *countingDoor) calls() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.n
}

func TestQuotaSpreadsCallsOverTheAccountsThatHaveADayLeft(t *testing.T) {
	fast := &countingDoor{name: "fast"}
	slow := &countingDoor{name: "slow"}
	rotator := relay.NewRotator(fast, slow)
	quota := relay.NewQuota(2, "")
	door := relay.NewQuotaRotator(rotator, quota)

	ctx := context.Background()
	for i := 0; i < 4; i++ {
		if _, err := door.Call(ctx, proto.Request{Op: proto.OpPing}); err != nil {
			t.Fatalf("call %d: %v", i, err)
		}
	}
	if fast.calls() != 2 || slow.calls() != 2 {
		t.Fatalf("calls are fast=%d slow=%d, want 2 and 2: the quota must spread, not drain the fastest",
			fast.calls(), slow.calls())
	}
	if _, err := door.Call(ctx, proto.Request{Op: proto.OpPing}); err != relay.ErrQuotaSpent {
		t.Fatalf("an overspent day gave %v, want ErrQuotaSpent", err)
	}
	if fast.calls() != 2 || slow.calls() != 2 {
		t.Fatalf("a refused call still reached a door: fast=%d slow=%d", fast.calls(), slow.calls())
	}
}

func TestAnUnlimitedBudgetNeverRefuses(t *testing.T) {
	a := &countingDoor{name: "a"}
	b := &countingDoor{name: "b"}
	door := relay.NewQuotaRotator(relay.NewRotator(a, b), relay.NewQuota(0, ""))
	for i := 0; i < 50; i++ {
		if _, err := door.Call(context.Background(), proto.Request{Op: proto.OpPing}); err != nil {
			t.Fatalf("call %d: %v", i, err)
		}
	}
	if a.calls() != 50 {
		t.Fatalf("the health order was not kept: a=%d b=%d", a.calls(), b.calls())
	}
	if left := door.Quota().Remaining("a"); left != -1 {
		t.Fatalf("Remaining without a budget = %d, want -1", left)
	}
}

func TestTheDayIsRememberedAcrossARestartAndForgottenTheNextDay(t *testing.T) {
	path := filepath.Join(t.TempDir(), "quota.json")
	first := relay.NewQuota(10, path)
	for i := 0; i < 3; i++ {
		if !first.Spend("door-a") {
			t.Fatalf("call %d was refused", i)
		}
	}
	first.Close()

	restarted := relay.NewQuota(10, path)
	if left := restarted.Remaining("door-a"); left != 7 {
		t.Fatalf("after a restart %d calls were left, want 7: the day was forgotten", left)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var saved struct {
		Day  string         `json:"day"`
		Used map[string]int `json:"used"`
	}
	if err := json.Unmarshal(raw, &saved); err != nil {
		t.Fatalf("the saved file is not the documented JSON: %v", err)
	}
	if saved.Used["door-a"] != 3 {
		t.Fatalf("saved used = %v", saved.Used)
	}

	// A file written yesterday says nothing about today.
	if err := os.WriteFile(path, []byte(`{"day":"2001-01-01","used":{"door-a":9}}`), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	tomorrow := relay.NewQuota(10, path)
	if left := tomorrow.Remaining("door-a"); left != 10 {
		t.Fatalf("yesterday's counters survived into today: %d left", left)
	}
}

func TestAFailingDoorIsSkippedWithoutSpendingTheOthersDry(t *testing.T) {
	broken := &countingDoor{name: "broken", err: errors.New("boom")}
	good := &countingDoor{name: "good"}
	door := relay.NewQuotaRotator(relay.NewRotator(broken, good), relay.NewQuota(5, ""))

	if _, err := door.Call(context.Background(), proto.Request{Op: proto.OpPing}); err != nil {
		t.Fatalf("a broken door stopped a working one: %v", err)
	}
	if good.calls() != 1 {
		t.Fatalf("good door took %d calls, want 1", good.calls())
	}
	if left := door.Quota().Remaining("good"); left != 4 {
		t.Fatalf("the working door has %d calls left, want 4", left)
	}
	// The broken door was asked once and demoted; the next call goes to the working one first.
	if _, err := door.Call(context.Background(), proto.Request{Op: proto.OpPing}); err != nil {
		t.Fatalf("second call: %v", err)
	}
	if good.calls() != 2 {
		t.Fatalf("good door took %d calls, want 2", good.calls())
	}
}

func TestQuotaCountsThroughADoorOverTheWire(t *testing.T) {
	// The counting door is a stand-in; this proves the rotator composes with a real door as well.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()

	real := relay.NewDirect(srv.URL, "k")
	door := relay.NewQuotaRotator(relay.NewRotator(real), relay.NewQuota(1, ""))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := door.Call(ctx, proto.Request{Op: proto.OpPing}); err != nil {
		t.Fatalf("first call: %v", err)
	}
	if _, err := door.Call(ctx, proto.Request{Op: proto.OpPing}); err == nil {
		t.Fatal("a spent day went over budget without complaining")
	}
}
