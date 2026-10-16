package main

import (
	"math"
	"testing"
)

func detector() *Detector {
	return NewDetector(SymbolConfig{Symbol: "BTCUSDT", Thresholds: []float64{1, 1.5, 2}, WickPercent: 1.5}, 15000, 300000)
}
func tick(sec int, p float64) Tick {
	return Tick{T: 1700000000000 + int64(sec)*1000, P: p, ID: int64(sec + 1)}
}
func add(d *Detector, s int, p float64) []Event { x := tick(s, p); return d.Add(x, x.T) }
func count(events []Event, kind string) int {
	n := 0
	for _, e := range events {
		if e.Kind == kind {
			n++
		}
	}
	return n
}
func TestFullWindowAndCooldown(t *testing.T) {
	d := detector()
	for i := 0; i < 60; i++ {
		if ev := add(d, i, 100); len(ev) != 0 {
			t.Fatal(ev)
		}
	}
	e := add(d, 60, 98)
	if count(e, "rapid_drop") != 1 {
		t.Fatalf("expected 1m drop %+v", e)
	}
	if e[0].Notification.Mode != "alarm" || e[0].Notification.Sound || e[0].Notification.SoundPolicy != "device_local_schedule" || !e[0].Notification.Vibration {
		t.Fatal("market alarm must defer sound to the device schedule and allow vibration", e[0])
	}
	if count(add(d, 61, 97), "rapid_drop") != 0 {
		t.Fatal("cooldown")
	}
	if d.Last.P != 97 {
		t.Fatal("last")
	}
}
func TestShortObservationCannotFire(t *testing.T) {
	d := detector()
	add(d, 0, 100)
	if ev := add(d, 1, 50); len(ev) != 0 {
		t.Fatal(ev)
	}
}
func TestGapResetsCoverage(t *testing.T) {
	d := detector()
	for i := 0; i < 60; i++ {
		add(d, i, 100)
	}
	e := add(d, 100, 50)
	if count(e, "data_gap") != 1 || count(e, "rapid_drop") != 0 || e[0].AlertEligible || e[0].Notification.Mode != "record_only" {
		t.Fatal(e)
	}
	if d.Started != tick(100, 50).T {
		t.Fatal("reset")
	}
}
func TestRejectStaleFutureDuplicateAndInvalid(t *testing.T) {
	d := detector()
	x := tick(0, 100)
	for _, now := range []int64{x.T + 16000, x.T - 6000} {
		if len(d.Add(x, now)) != 0 || d.Last.T != 0 {
			t.Fatal("bad freshness")
		}
	}
	d.Add(x, x.T)
	d.Add(Tick{T: x.T - 1, P: 1, ID: x.ID + 1}, x.T)
	d.Add(Tick{T: x.T + 1, P: 1, ID: x.ID}, x.T+1)
	for _, p := range []float64{0, -1, math.NaN(), math.Inf(1)} {
		d.Add(Tick{T: x.T + 2, P: p, ID: x.ID + 2}, x.T+2)
	}
	if d.Last.P != 100 || d.Rejected != 8 {
		t.Fatalf("%+v", d)
	}
}
func TestWickOrderAndRecovery(t *testing.T) {
	d := detector()
	for i := 0; i <= 300; i++ {
		add(d, i, 100)
	}
	add(d, 301, 97)
	ev := add(d, 302, 99.5)
	if count(ev, "down_wick_rebound") != 1 {
		t.Fatal(ev)
	}
	for _, e := range ev {
		if e.Kind == "down_wick_rebound" {
			if e.Details["recovery_fraction"].(float64) < 0.6 {
				t.Fatal(e)
			}
		}
	}
	if count(add(d, 303, 99.8), "down_wick_rebound") != 0 {
		t.Fatal("wick repeated")
	}
}
func TestLowBeforeHighIsNotDownWick(t *testing.T) {
	d := detector()
	for i := 0; i <= 300; i++ {
		add(d, i, 97)
	}
	ev := add(d, 301, 100)
	if count(ev, "down_wick_rebound") != 0 {
		t.Fatal(ev)
	}
}
func TestRiseAndUpReversal(t *testing.T) {
	d := detector()
	for i := 0; i <= 300; i++ {
		add(d, i, 100)
	}
	if count(add(d, 301, 104), "rapid_rise") != 3 {
		t.Fatal("rise windows")
	}
	if ev := add(d, 302, 101); count(ev, "up_wick_reversal") != 1 {
		t.Fatal(ev)
	}
}
func TestResetRetainsCooldown(t *testing.T) {
	d := detector()
	d.Cooldown["rapid_drop:60000"] = 123
	d.Reset()
	if d.Cooldown["rapid_drop:60000"] != 123 {
		t.Fatal("lost cooldown")
	}
}
func TestAsOfBaselineNotFuture(t *testing.T) {
	d := detector()
	for i := 0; i < 70; i += 2 {
		add(d, i, 100)
	}
	ev := add(d, 71, 98)
	if count(ev, "rapid_drop") != 1 {
		t.Fatal(ev)
	}
	b := ev[0].Details["baseline"].(Tick)
	if b.T > tick(11, 0).T {
		t.Fatal("future baseline")
	}
}
func TestDifferentWindowsHaveDifferentIDs(t *testing.T) {
	d := detector()
	for i := 0; i <= 300; i++ {
		add(d, i, 100)
	}
	ev := add(d, 301, 104)
	ids := map[string]bool{}
	for _, e := range ev {
		if ids[e.ID] {
			t.Fatalf("event collision %s", e.ID)
		}
		ids[e.ID] = true
	}
	if len(ids) != 3 {
		t.Fatal(ev)
	}
}
func TestIntrasecondCrashStillTriggers(t *testing.T) {
	d := detector()
	for i := 0; i <= 300; i++ {
		add(d, i, 100)
	}
	x := tick(300, 95)
	x.T += 100
	x.ID++
	if ev := d.Add(x, x.T); count(ev, "rapid_drop") != 3 {
		t.Fatal(ev)
	}
}
