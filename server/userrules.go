package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
)

// UserRule mirrors the phone's AlarmPreset. The phone owns editing; the server stores and evaluates.
type UserRule struct {
	ID              string  `json:"id"`
	Name            string  `json:"name"`
	Coin            string  `json:"coin"`
	Kind            string  `json:"kind"`
	Value           float64 `json:"value"`
	Minutes         int     `json:"minutes"`
	Enabled         bool    `json:"enabled"`
	Duration        int     `json:"duration"`
	Sound           bool    `json:"sound"`
	Vibration       bool    `json:"vibration"`
	Repeat          bool    `json:"repeat"`
	CooldownMinutes int     `json:"cooldownMinutes"`
	LastFired       int64   `json:"lastFired"`
}

type UserRuleSet struct {
	Rules     []UserRule `json:"rules"`
	UpdatedAt int64      `json:"updated_at_ms"`
}

var coinPattern = regexp.MustCompile(`^[A-Z0-9]{1,30}$`)

func validUserRules(rules []UserRule) error {
	if len(rules) > 20 {
		return errors.New("at most 20 rules")
	}
	ids := map[string]bool{}
	for _, r := range rules {
		if r.ID == "" || len(r.ID) > 100 || ids[r.ID] || !coinPattern.MatchString(r.Coin) || len([]rune(r.Name)) > 40 {
			return fmt.Errorf("invalid rule %q", r.ID)
		}
		ids[r.ID] = true
		if math.IsNaN(r.Value) || math.IsInf(r.Value, 0) || r.Value <= 0 || r.Minutes < 1 || r.Minutes > 60 {
			return fmt.Errorf("invalid rule values %q", r.ID)
		}
		switch r.Kind {
		case "drop", "rise":
			if r.Value >= 100 {
				return fmt.Errorf("percent must be below 100: %q", r.ID)
			}
		case "price", "above":
		default:
			return fmt.Errorf("unknown kind %q", r.Kind)
		}
		if r.Duration < 10 || r.Duration > 120 || r.CooldownMinutes < 1 || r.CooldownMinutes > 1440 {
			return fmt.Errorf("invalid duration or cooldown %q", r.ID)
		}
	}
	return nil
}

func (r UserRule) Symbol() string { return r.Coin + "USDT" }

type ruleSample struct {
	T  int64
	P  float64
	ID int64
}

// ruleSeries keeps one sample per second for an hour: enough for any 1–60 minute as-of baseline.
type ruleSeries struct {
	samples []ruleSample
	started int64
}

type ruleMonitor struct {
	armed bool
	rule  UserRule
}

// UserRuleEngine evaluates phone-defined rules on the server's trade stream.
type UserRuleEngine struct {
	mu       sync.Mutex
	path     string
	set      UserRuleSet
	series   map[string]*ruleSeries
	monitors map[string]*ruleMonitor
	status   map[string]string
	GapMS    int64
	// Per-symbol tolerated trade gaps (sparse commodities); others use GapMS.
	Gaps map[string]int64
}

func (e *UserRuleEngine) gap(symbol string) int64 {
	if g := e.Gaps[symbol]; g > 0 {
		return g
	}
	return e.GapMS
}

func OpenUserRules(dir string, gapMS int64) (*UserRuleEngine, error) {
	e := &UserRuleEngine{path: filepath.Join(dir, "user-rules.json"), series: map[string]*ruleSeries{}, monitors: map[string]*ruleMonitor{}, status: map[string]string{}, GapMS: gapMS}
	b, err := os.ReadFile(e.path)
	if errors.Is(err, os.ErrNotExist) {
		return e, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, &e.set); err != nil {
		return nil, fmt.Errorf("invalid %s: %w", e.path, err)
	}
	if err := validUserRules(e.set.Rules); err != nil {
		return nil, fmt.Errorf("invalid %s: %w", e.path, err)
	}
	return e, nil
}

// persist writes atomically; the caller holds mu.
func (e *UserRuleEngine) persist() error {
	b, err := json.MarshalIndent(e.set, "", "  ")
	if err != nil {
		return err
	}
	tmp := e.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	if ce := f.Close(); err == nil {
		err = ce
	}
	if err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, e.path)
}

// Replace installs a new rule set from the phone. Monitors restart so a changed rule never fires on stale state.
func (e *UserRuleEngine) Replace(rules []UserRule, now int64) (UserRuleSet, error) {
	if rules == nil {
		rules = []UserRule{}
	}
	if err := validUserRules(rules); err != nil {
		return UserRuleSet{}, err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	previous := e.set
	e.set = UserRuleSet{Rules: rules, UpdatedAt: now}
	if err := e.persist(); err != nil {
		e.set = previous
		return UserRuleSet{}, err
	}
	next := map[string]*ruleMonitor{}
	for _, r := range rules {
		if m, ok := e.monitors[r.ID]; ok && m.rule == r {
			next[r.ID] = m
		}
	}
	e.monitors = next
	return e.set, nil
}

func (e *UserRuleEngine) Snapshot() (UserRuleSet, map[string]string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := e.set
	out.Rules = append([]UserRule{}, e.set.Rules...)
	status := map[string]string{}
	for k, v := range e.status {
		status[k] = v
	}
	return out, status
}

// Symbols lists every Binance symbol an enabled rule needs, sorted.
func (e *UserRuleEngine) Symbols() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	seen := map[string]bool{}
	out := []string{}
	for _, r := range e.set.Rules {
		if r.Enabled && !seen[r.Symbol()] {
			seen[r.Symbol()] = true
			out = append(out, r.Symbol())
		}
	}
	sort.Strings(out)
	return out
}

// Reset drops observation history after a disconnect; no gap is ever filled in.
func (e *UserRuleEngine) Reset() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.series = map[string]*ruleSeries{}
	for _, m := range e.monitors {
		m.armed = false
	}
}

func (e *UserRuleEngine) Add(symbol string, t Tick, now int64) []Event {
	if t.T <= 0 || t.P <= 0 || math.IsNaN(t.P) || math.IsInf(t.P, 0) || now-t.T > 15000 || t.T-now > 5000 {
		return nil
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	s := e.series[symbol]
	if s == nil {
		s = &ruleSeries{started: t.T}
		e.series[symbol] = s
	}
	if n := len(s.samples); n > 0 {
		last := s.samples[n-1]
		if t.T < last.T {
			return nil
		}
		if t.T-last.T > e.gap(symbol) {
			*s = ruleSeries{started: t.T}
			for _, r := range e.set.Rules {
				if r.Symbol() == symbol {
					delete(e.monitors, r.ID)
				}
			}
		}
	}
	x := ruleSample{t.T, t.P, t.ID}
	if n := len(s.samples); n > 0 && s.samples[n-1].T/1000 == t.T/1000 {
		s.samples[n-1] = x
	} else {
		s.samples = append(s.samples, x)
	}
	if i := sort.Search(len(s.samples), func(i int) bool { return s.samples[i].T > t.T-3600000 }) - 1; i > 0 {
		s.samples = s.samples[i:]
	}
	out := []Event{}
	changed := false
	for i, r := range e.set.Rules {
		if !r.Enabled || r.Symbol() != symbol {
			continue
		}
		if r.LastFired > 0 && now-r.LastFired < int64(r.CooldownMinutes)*60000 {
			e.status[r.ID] = fmt.Sprintf("冷却中 · %d 分钟", r.CooldownMinutes)
			delete(e.monitors, r.ID)
			continue
		}
		m := e.monitors[r.ID]
		if m == nil || m.rule != r {
			m = &ruleMonitor{rule: r}
			e.monitors[r.ID] = m
		}
		title, detail, fire := e.evaluate(r, m, s, x, e.gap(symbol))
		if !fire {
			continue
		}
		// Persist one-shot disarm / cooldown before the event is published.
		e.set.Rules[i].LastFired = now
		e.set.Rules[i].Enabled = r.Repeat
		delete(e.monitors, r.ID)
		changed = true
		out = append(out, Event{
			ID: fmt.Sprintf("user_rule:%s:%d", r.ID, t.ID), Kind: "user_rule", Source: "binance_usdm_aggTrade", Symbol: symbol,
			Observed: now, Published: t.T, Title: title, AlertEligible: true,
			Details:      map[string]any{"rule_id": r.ID, "rule_name": r.Name, "detail": detail, "price": t.P, "duration": r.Duration},
			Notification: &NotificationPlan{Mode: "alarm", Sound: r.Sound, SoundPolicy: "device_local_schedule", Vibration: r.Vibration},
		})
	}
	if changed {
		e.set.UpdatedAt = now
		if err := e.persist(); err != nil {
			e.status["_storage"] = err.Error()
		}
	}
	return out
}

// evaluate matches the phone's PriceAlarmMonitor: full observed window, crossings need a prior opposite side.
func (e *UserRuleEngine) evaluate(r UserRule, m *ruleMonitor, s *ruleSeries, x ruleSample, gap int64) (string, string, bool) {
	price := formatPrice(x.P)
	if r.Kind == "price" || r.Kind == "above" {
		up := r.Kind == "above"
		fire := m.armed && ((up && x.P >= r.Value) || (!up && x.P <= r.Value))
		m.armed = (up && x.P < r.Value) || (!up && x.P > r.Value)
		verb := "跌破"
		if up {
			verb = "突破"
		}
		if m.armed {
			e.status[r.ID] = fmt.Sprintf("等待%s %s", verb, formatPrice(r.Value))
		} else {
			e.status[r.ID] = "已在阈值触发侧，需返回另一侧后再穿越"
		}
		return fmt.Sprintf("%s %s价格", r.Coin, verb), fmt.Sprintf("现价 %s USDT · 阈值 %s", price, formatPrice(r.Value)), fire
	}
	window := int64(r.Minutes) * 60000
	target := x.T - window
	i := sort.Search(len(s.samples), func(i int) bool { return s.samples[i].T > target }) - 1
	if s.started > target || i < 0 || target-s.samples[i].T > gap {
		e.status[r.ID] = fmt.Sprintf("采集窗口：%ds / %ds", (x.T-s.started)/1000, window/1000)
		return "", "", false
	}
	base := s.samples[i]
	up := r.Kind == "rise"
	move := (1 - x.P/base.P) * 100
	direction, label := "下跌", "急跌"
	if up {
		move, direction, label = (x.P/base.P-1)*100, "上涨", "急涨"
	}
	e.status[r.ID] = fmt.Sprintf("%d 分钟%s %.2f%% · 阈值 %g%%", r.Minutes, direction, move, r.Value)
	return fmt.Sprintf("%s 短时%s", r.Coin, label), fmt.Sprintf("%d 分钟%s %.2f%% · 现价 %s USDT", r.Minutes, direction, move, price), move >= r.Value
}

func formatPrice(v float64) string {
	switch {
	case v >= 1000:
		return fmt.Sprintf("%.2f", v)
	case v >= 1:
		return fmt.Sprintf("%.3f", v)
	case v >= .01:
		return fmt.Sprintf("%.4f", v)
	case v >= .0001:
		return fmt.Sprintf("%.6f", v)
	}
	return fmt.Sprintf("%.8f", v)
}
