package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

type MoomooConfig struct {
	Enabled     bool      `json:"enabled"`
	Python      string    `json:"python"`
	Script      string    `json:"script"`
	Host        string    `json:"host"`
	Port        int       `json:"port"`
	Symbols     []string  `json:"symbols"`
	Thresholds  []float64 `json:"thresholds"`
	WickPercent float64   `json:"wick_percent"`
}
type MoomooStatus struct {
	Enabled       bool              `json:"enabled"`
	LastHeartbeat int64             `json:"last_heartbeat_ms"`
	Permission    string            `json:"permission"`
	Quota         int               `json:"subscription_quota"`
	Error         string            `json:"error"`
	States        map[string]string `json:"market_states"`
	Session       map[string]string `json:"sessions"`
	SDK           string            `json:"sdk"`
}

func (a *App) startMoomoo(ctx context.Context) {
	a.moomoo = MoomooStatus{Enabled: a.cfg.Moomoo.Enabled, States: map[string]string{}, Session: map[string]string{}}
	if !a.cfg.Moomoo.Enabled {
		return
	}
	for _, s := range a.cfg.Moomoo.Symbols {
		d := NewDetector(SymbolConfig{Symbol: s, Label: s, Thresholds: a.cfg.Moomoo.Thresholds, WickPercent: a.cfg.Moomoo.WickPercent}, a.cfg.GapSeconds*1000, a.cfg.CooldownSeconds*1000)
		d.Source = "moomoo_us_ticker"
		a.restoreCooldown(d)
		a.det[s] = d
	}
	go func() {
		delay := time.Second
		for ctx.Err() == nil {
			err := a.moomooProcess(ctx)
			a.mu.Lock()
			a.moomoo.Error = fmt.Sprint(err)
			for _, s := range a.cfg.Moomoo.Symbols {
				a.det[s].Reset()
				a.det[s].Gaps++
			}
			a.mu.Unlock()
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
			}
			delay = min(delay*2, time.Minute)
		}
	}()
}
func (a *App) moomooProcess(ctx context.Context) error {
	c := a.cfg.Moomoo
	child, cancel := context.WithCancel(ctx)
	defer cancel()
	cmd := exec.CommandContext(child, c.Python, c.Script, "--symbols", strings.Join(c.Symbols, ","), "--host", c.Host, "--port", fmt.Sprint(c.Port))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err = cmd.Start(); err != nil {
		return err
	}
	started := time.Now().UnixMilli()
	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-child.Done():
				return
			case <-ticker.C:
				a.mu.Lock()
				last := max(started, a.moomoo.LastHeartbeat)
				a.mu.Unlock()
				if time.Now().UnixMilli()-last > 90000 {
					cancel()
					return
				}
			}
		}
	}()
	scan := bufio.NewScanner(stdout)
	scan.Buffer(make([]byte, 4096), 1<<20)
	for scan.Scan() {
		line := scan.Text()
		if !strings.HasPrefix(line, "PNALE ") {
			continue
		}
		if err = a.moomooMessage([]byte(strings.TrimPrefix(line, "PNALE "))); err != nil {
			cancel()
			break
		}
	}
	if se := scan.Err(); se != nil {
		err = se
		cancel()
	}
	wait := cmd.Wait()
	if err != nil {
		return err
	}
	return wait
}
func (a *App) moomooMessage(b []byte) error {
	var m struct {
		Type       string            `json:"type"`
		Time       int64             `json:"time_ms"`
		Permission string            `json:"permission"`
		Quota      int               `json:"quota"`
		States     map[string]string `json:"states"`
		SDK        string            `json:"sdk"`
		Symbol     string            `json:"symbol"`
		T          int64             `json:"t"`
		P          float64           `json:"p"`
		ID         int64             `json:"id"`
		Session    string            `json:"session"`
		Message    string            `json:"message"`
	}
	if err := json.Unmarshal(b, &m); err != nil {
		return err
	}
	if m.Type == "error" {
		return fmt.Errorf("moomoo: %s", m.Message)
	}
	a.mu.Lock()
	var events []Event
	switch m.Type {
	case "state":
		a.moomoo.LastHeartbeat = m.Time
		a.moomoo.Permission = m.Permission
		a.moomoo.Quota = m.Quota
		a.moomoo.States = m.States
		a.moomoo.SDK = m.SDK
		a.moomoo.Error = ""
	case "tick":
		if d := a.det[m.Symbol]; d != nil && d.Source == "moomoo_us_ticker" {
			if a.moomoo.Session[m.Symbol] != m.Session {
				d.Reset()
				a.moomoo.Session[m.Symbol] = m.Session
			}
			events = d.Add(Tick{T: m.T, P: m.P, ID: m.ID}, time.Now().UnixMilli())
		}
	}
	a.mu.Unlock()
	for _, e := range events {
		a.record(e)
	}
	return nil
}
func marketClosed(state string) bool {
	switch state {
	case "CLOSED", "REST", "AFTER_HOURS_END", "PRE_MARKET_END", "OVERNIGHT_END", "FUTURE_CLOSE":
		return true
	}
	return false
}
