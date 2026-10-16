package main

import (
	"fmt"
	"math"
	"sort"
)

type SymbolConfig struct {
	MaxTradeGapSeconds int64     `json:"max_trade_gap_seconds,omitempty"`
	Symbol             string    `json:"symbol"`
	Label              string    `json:"label"`
	Thresholds         []float64 `json:"thresholds"`
	WickPercent        float64   `json:"wick_percent"`
}
type Tick struct {
	T  int64   `json:"time_ms"`
	P  float64 `json:"price"`
	ID int64   `json:"trade_id"`
}
type Event struct {
	ID            string            `json:"id"`
	Kind          string            `json:"kind"`
	Source        string            `json:"source"`
	Symbol        string            `json:"symbol,omitempty"`
	Observed      int64             `json:"observed_at_ms"`
	Published     int64             `json:"published_at_ms"`
	Title         string            `json:"title"`
	Details       map[string]any    `json:"details"`
	AlertEligible bool              `json:"alert_eligible"`
	Notification  *NotificationPlan `json:"notification,omitempty"`
}

type NotificationPlan struct {
	Mode        string `json:"mode"`
	Sound       bool   `json:"sound,omitempty"`
	SoundPolicy string `json:"sound_policy,omitempty"`
	Vibration   bool   `json:"vibration,omitempty"`
}
type Detector struct {
	Source     string
	Config     SymbolConfig
	Ticks      []Tick
	Last       Tick
	Started    int64
	LastEval   int64
	Gaps       int64
	Rejected   int64
	Cooldown   map[string]int64
	GapMS      int64
	CooldownMS int64
}

func NewDetector(c SymbolConfig, gap, cool int64) *Detector {
	return &Detector{Config: c, Source: "binance_usdm_aggTrade", GapMS: gap, CooldownMS: cool, Cooldown: map[string]int64{}}
}
func (d *Detector) Reset() { d.Ticks = nil; d.Started = 0; d.Last = Tick{}; d.LastEval = 0 }
func (d *Detector) event(kind string, t Tick, now int64, detail map[string]any) Event {
	source := d.Source
	if source == "" {
		source = "binance_usdm_aggTrade"
	}
	plan := &NotificationPlan{Mode: "record_only"}
	if kind == "rapid_drop" || kind == "rapid_rise" || kind == "down_wick_rebound" || kind == "up_wick_reversal" {
		plan = &NotificationPlan{Mode: "alarm", SoundPolicy: "device_local_schedule", Vibration: true}
	}
	return Event{ID: fmt.Sprintf("%s:%s:%s:%d", source, d.Config.Symbol, kind, t.ID), Kind: kind, Source: source, Symbol: d.Config.Symbol, Observed: now, Published: t.T, Title: d.Config.Symbol + " " + kind, Details: detail, AlertEligible: true, Notification: plan}
}
func (d *Detector) Add(t Tick, now int64) []Event {
	if t.T <= 0 || t.ID <= 0 || t.P <= 0 || math.IsNaN(t.P) || math.IsInf(t.P, 0) || now-t.T > 15000 || t.T-now > 5000 {
		d.Rejected++
		return nil
	}
	if d.Last.T > 0 && (t.ID <= d.Last.ID || t.T < d.Last.T) {
		d.Rejected++
		return nil
	}
	out := []Event{}
	if d.Last.T > 0 && t.T-d.Last.T > d.GapMS {
		out = append(out, d.event("data_gap", t, now, map[string]any{"previous_time_ms": d.Last.T, "gap_ms": t.T - d.Last.T}))
		out[len(out)-1].AlertEligible = false
		d.Gaps++
		d.Reset()
	}
	if len(d.Ticks) >= 200000 {
		out = append(out, d.event("buffer_reset", t, now, map[string]any{"reason": "200000 tick capacity reached"}))
		out[len(out)-1].AlertEligible = false
		d.Gaps++
		d.Reset()
	}
	if d.Started == 0 {
		d.Started = t.T
	}
	d.Last = t
	d.Ticks = append(d.Ticks, t)
	// Retain the last observation before the longest target for an as-of baseline.
	i := sort.Search(len(d.Ticks), func(i int) bool { return d.Ticks[i].T > t.T-300000 }) - 1
	if i > 0 {
		d.Ticks = d.Ticks[i:]
	}
	wickDue := t.T-d.LastEval >= 1000
	if wickDue {
		d.LastEval = t.T
	}
	emit := func(kind, key string, detail map[string]any) {
		if last, ok := d.Cooldown[key]; !ok || now-last >= d.CooldownMS {
			d.Cooldown[key] = now
			e := d.event(kind, t, now, detail)
			e.ID = fmt.Sprintf("%s:%s", e.ID, key)
			out = append(out, e)
		}
	}
	for j, w := range []int64{60000, 180000, 300000} {
		target := t.T - w
		if t.T-d.Started < w {
			continue
		}
		i := sort.Search(len(d.Ticks), func(i int) bool { return d.Ticks[i].T > target }) - 1
		if i < 0 || target-d.Ticks[i].T > d.GapMS {
			continue
		}
		base := d.Ticks[i]
		ret := (t.P/base.P - 1) * 100
		kind := "rapid_rise"
		if ret < 0 {
			kind = "rapid_drop"
		}
		if math.Abs(ret) >= d.Config.Thresholds[j] {
			emit(kind, fmt.Sprintf("%s:%d", kind, w), map[string]any{"window_seconds": w / 1000, "return_percent": ret, "threshold_percent": d.Config.Thresholds[j], "baseline": base, "current": t})
		}
	}
	// Ordered trades establish extremum order; evaluate completed 5-minute coverage only.
	if wickDue && t.T-d.Started >= 300000 && len(d.Ticks) > 1 {
		start := sort.Search(len(d.Ticks), func(i int) bool { return d.Ticks[i].T >= t.T-300000 })
		seq := d.Ticks[start:]
		hi, lo := seq[0], seq[0]
		peak, trough := hi, hi
		bottom, top := lo, lo
		maxDown, maxUp := 0.0, 0.0
		for _, x := range seq {
			down := (hi.P - x.P) / hi.P * 100
			if down > maxDown {
				maxDown = down
				peak = hi
				trough = x
			}
			if x.P > hi.P {
				hi = x
			}
			up := (x.P - lo.P) / lo.P * 100
			if up > maxUp {
				maxUp = up
				bottom = lo
				top = x
			}
			if x.P < lo.P {
				lo = x
			}
		}
		if maxDown >= d.Config.WickPercent && trough.ID < t.ID && peak.ID < trough.ID {
			recovery := (t.P - trough.P) / (peak.P - trough.P)
			if recovery >= 0.6 {
				emit("down_wick_rebound", "down_wick_rebound", map[string]any{"window_seconds": 300, "excursion_percent": maxDown, "recovery_fraction": recovery, "peak": peak, "trough": trough, "current": t})
			}
		}
		if maxUp >= d.Config.WickPercent && top.ID < t.ID && bottom.ID < top.ID {
			recovery := (top.P - t.P) / (top.P - bottom.P)
			if recovery >= 0.6 {
				emit("up_wick_reversal", "up_wick_reversal", map[string]any{"window_seconds": 300, "excursion_percent": maxUp, "recovery_fraction": recovery, "bottom": bottom, "top": top, "current": t})
			}
		}
	}
	return out
}
