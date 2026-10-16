package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"maps"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Config struct {
	Listen          string         `json:"listen"`
	DataDir         string         `json:"data_dir"`
	WSURL           string         `json:"ws_url"`
	RESTURL         string         `json:"rest_url"`
	GapSeconds      int64          `json:"gap_seconds"`
	CooldownSeconds int64          `json:"cooldown_seconds"`
	Symbols         []SymbolConfig `json:"symbols"`
	News            NewsConfig     `json:"news"`
	Moomoo          MoomooConfig   `json:"moomoo"`
}
type App struct {
	mu         sync.Mutex
	moomoo     MoomooStatus
	cfg        Config
	store      *Store
	det        map[string]*Detector
	connected  bool
	lastError  string
	news       map[string]NewsStatus
	started    int64
	reconnects int64
	contracts  map[string]string
	rules      *UserRuleEngine
	resub      chan struct{}
}

var client = &http.Client{Timeout: 15 * time.Second}

func getJSON(ctx context.Context, u string, header http.Header, out any) error {
	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return errors.New("invalid source URL")
	}
	req.Header = header
	if req.Header == nil {
		req.Header = http.Header{}
	}
	req.Header.Set("User-Agent", "PnaleMonitor/0.1")
	r, err := client.Do(req)
	if err != nil {
		return errors.New("source network request failed")
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return fmt.Errorf("source HTTP %d", r.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(out)
}
func validConfig(c Config) error {
	if len(c.Symbols) == 0 || c.GapSeconds < 1 || c.GapSeconds > 30 || c.CooldownSeconds < 60 {
		return errors.New("invalid monitor settings")
	}
	if c.Moomoo.Enabled {
		m := c.Moomoo
		if len(m.Symbols) == 0 || len(m.Symbols) > 100 || len(m.Thresholds) != 3 || m.WickPercent <= 0 || m.Python == "" || m.Script == "" {
			return errors.New("invalid moomoo settings")
		}
		unique := map[string]bool{}
		for _, sym := range m.Symbols {
			if !strings.HasPrefix(sym, "US.") || unique[sym] {
				return errors.New("invalid US watchlist")
			}
			unique[sym] = true
		}
		for _, v := range m.Thresholds {
			if v <= 0 || v >= 100 || math.IsNaN(v) {
				return errors.New("invalid moomoo threshold")
			}
		}
	}
	seen := map[string]bool{}
	for _, s := range c.Symbols {
		if s.Symbol == "" || seen[s.Symbol] || len(s.Thresholds) != 3 || s.WickPercent <= 0 || s.MaxTradeGapSeconds < 0 || s.MaxTradeGapSeconds > 300 || math.IsNaN(s.WickPercent) {
			return errors.New("invalid symbol config")
		}
		seen[s.Symbol] = true
		for _, v := range s.Thresholds {
			if v <= 0 || v >= 100 || math.IsNaN(v) {
				return errors.New("invalid threshold")
			}
		}
	}
	return nil
}
func (a *App) record(e Event) {
	if a.store.Contains(e.ID) {
		return
	}
	if err := a.store.Add(e); err != nil {
		log.Printf("event persistence failed: %v", err)
	} else {
		log.Printf("event id=%s kind=%s symbol=%s", e.ID, e.Kind, e.Symbol)
	}
}
func (a *App) validate(ctx context.Context) error {
	var info struct {
		Symbols []struct {
			Symbol string `json:"symbol"`
			Status string `json:"status"`
			Type   string `json:"contractType"`
			Quote  string `json:"quoteAsset"`
		} `json:"symbols"`
	}
	if err := getJSON(ctx, a.cfg.RESTURL+"/fapi/v1/exchangeInfo", nil, &info); err != nil {
		return err
	}
	types := map[string]string{}
	for _, s := range info.Symbols {
		if s.Status == "TRADING" && s.Quote == "USDT" && (s.Type == "PERPETUAL" || s.Type == "TRADIFI_PERPETUAL") {
			types[s.Symbol] = s.Type
		}
	}
	for _, s := range a.cfg.Symbols {
		if types[s.Symbol] == "" {
			return fmt.Errorf("configured symbol unavailable: %s", s.Symbol)
		}
	}
	a.mu.Lock()
	a.contracts = types
	a.mu.Unlock()
	return nil
}
func (a *App) stream(ctx context.Context) {
	delay := time.Second
	for ctx.Err() == nil {
		err := a.validate(ctx)
		if err == nil {
			err = a.connection(ctx)
		}
		a.mu.Lock()
		a.connected = false
		a.reconnects++
		a.lastError = fmt.Sprint(err)
		for _, d := range a.det {
			if d.Source == "moomoo_us_ticker" {
				continue
			}
			d.Reset()
			d.Gaps++
		}
		a.mu.Unlock()
		a.rules.Reset()
		log.Printf("market disconnected: %v; retry=%s", err, delay)
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if delay < 30*time.Second {
			delay *= 2
		}
	}
}
// streams is the union of configured symbols and those used by enabled phone rules.
func (a *App) streams() []string {
	seen := map[string]bool{}
	out := []string{}
	for _, s := range a.cfg.Symbols {
		seen[s.Symbol] = true
		out = append(out, strings.ToLower(s.Symbol)+"@aggTrade")
	}
	for _, s := range a.rules.Symbols() {
		if !seen[s] {
			seen[s] = true
			out = append(out, strings.ToLower(s)+"@aggTrade")
		}
	}
	return out
}
func (a *App) connection(ctx context.Context) error {
	ss := a.streams()
	u, err := url.Parse(a.cfg.WSURL)
	if err != nil {
		return err
	}
	q := u.Query()
	q.Set("streams", strings.Join(ss, "/"))
	u.RawQuery = q.Encode()
	dial := websocket.Dialer{Proxy: http.ProxyFromEnvironment, HandshakeTimeout: 15 * time.Second}
	c, _, err := dial.DialContext(ctx, u.String(), nil)
	if err != nil {
		return errors.New("websocket dial failed")
	}
	defer c.Close()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			c.Close()
		case <-done:
		}
	}()
	c.SetReadLimit(1 << 20)
	// New rule symbols are added with SUBSCRIBE on the live socket, so existing histories are kept.
	subscribed := map[string]bool{}
	for _, s := range ss {
		subscribed[s] = true
	}
	go func() {
		var id int64
		for {
			select {
			case <-done:
				return
			case <-a.resub:
				add := []string{}
				for _, s := range a.streams() {
					if !subscribed[s] {
						add = append(add, s)
					}
				}
				if len(add) == 0 {
					continue
				}
				id++
				c.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := c.WriteJSON(map[string]any{"method": "SUBSCRIBE", "params": add, "id": id}); err != nil {
					c.Close()
					return
				}
				for _, s := range add {
					subscribed[s] = true
				}
				log.Printf("subscribed rule streams %v", add)
			}
		}
	}()
	a.mu.Lock()
	a.connected = true
	a.lastError = ""
	a.mu.Unlock()
	log.Print("market websocket connected")
	for {
		c.SetReadDeadline(time.Now().Add(45 * time.Second))
		_, b, err := c.ReadMessage()
		if err != nil {
			return err
		}
		var msg struct {
			Data struct {
				Event     string `json:"e"`
				EventTime int64  `json:"E"`
				Symbol    string `json:"s"`
				Price     string `json:"p"`
				Time      int64  `json:"T"`
				ID        int64  `json:"a"`
			} `json:"data"`
		}
		if json.Unmarshal(b, &msg) != nil || msg.Data.Event != "aggTrade" {
			continue
		}
		p, err := strconv.ParseFloat(msg.Data.Price, 64)
		if err != nil {
			continue
		}
		now := time.Now().UnixMilli()
		tk := Tick{T: msg.Data.Time, P: p, ID: msg.Data.ID}
		a.mu.Lock()
		d := a.det[msg.Data.Symbol]
		var events []Event
		if d != nil {
			events = d.Add(tk, now)
		}
		a.mu.Unlock()
		events = append(events, a.rules.Add(msg.Data.Symbol, tk, now)...)
		for _, e := range events {
			a.record(e)
		}
	}
}
func (a *App) health() (map[string]any, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now().UnixMilli()
	healthy := a.connected
	quotes := map[string]any{}
	for s, d := range a.det {
		age := now - d.Last.T
		if d.Source == "moomoo_us_ticker" && marketClosed(a.moomoo.States[s]) {
			quotes[s] = map[string]any{"state": "market_closed", "market_state": a.moomoo.States[s], "last": d.Last, "source": d.Source, "session": a.moomoo.Session[s]}
			continue
		}
		state := "live"
		if d.Last.T == 0 {
			state = "warming"
			healthy = false
		} else if age > d.GapMS {
			state = "stale"
			healthy = false
		} else if d.Last.T-d.Started < 300000 {
			state = "warming"
			healthy = false
		} else if age > 15000 {
			state = "quiet"
		}
		quotes[s] = map[string]any{"state": state, "last": d.Last, "age_ms": age, "coverage_seconds": max(int64(0), d.Last.T-d.Started) / 1000, "gaps": d.Gaps, "rejected": d.Rejected, "contract_type": a.contracts[s], "label": d.Config.Label, "max_trade_gap_seconds": d.GapMS / 1000, "source": d.Source}
	}
	if a.moomoo.Enabled && (a.moomoo.LastHeartbeat == 0 || now-a.moomoo.LastHeartbeat > 90000 || a.moomoo.Error != "") {
		healthy = false
	}
	_, diskErr := a.store.Snapshot(0, 0)
	if diskErr != "" {
		healthy = false
	}
	for _, s := range a.news {
		if s.Enabled && (s.LastSuccess == 0 || now-s.LastSuccess > max(a.cfg.News.PollSeconds*3000, 180000) || s.Error != "") {
			healthy = false
		}
	}
	moomoo := a.moomoo
	moomoo.States = maps.Clone(a.moomoo.States)
	moomoo.Session = maps.Clone(a.moomoo.Session)
	return map[string]any{"ok": healthy, "mode": "record_only", "started_at_ms": a.started, "now_ms": now, "connected": a.connected, "market_error": a.lastError, "reconnects": a.reconnects, "storage_error": diskErr, "symbols": quotes, "news": maps.Clone(a.news), "moomoo": moomoo}, healthy
}
func jsonOut(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}
func (a *App) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		v, ok := a.health()
		w.Header().Set("Content-Type", "application/json")
		if !ok {
			w.WriteHeader(503)
		}
		json.NewEncoder(w).Encode(v)
	})
	mux.HandleFunc("GET /v1/status", func(w http.ResponseWriter, r *http.Request) { v, _ := a.health(); jsonOut(w, v) })
	mux.HandleFunc("GET /v1/events", func(w http.ResponseWriter, r *http.Request) {
		after, _ := strconv.ParseInt(r.URL.Query().Get("after_ms"), 10, 64)
		lim, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if lim <= 0 || lim > 1000 {
			lim = 100
		}
		ev, err := a.store.Snapshot(after, lim)
		if kind := r.URL.Query().Get("kind"); kind != "" {
			filtered := []Event{}
			for _, e := range ev {
				if e.Kind == kind {
					filtered = append(filtered, e)
				}
			}
			ev = filtered
		}
		jsonOut(w, map[string]any{"events": ev, "storage_error": err, "order": "newest_first", "limit": lim, "retained_in_memory": 5000})
	})
	mux.HandleFunc("GET /v1/rules", func(w http.ResponseWriter, r *http.Request) {
		jsonOut(w, map[string]any{"symbols": a.cfg.Symbols, "windows_seconds": []int{60, 180, 300}, "wick_window_seconds": 300, "wick_recovery_fraction": 0.6, "cooldown_seconds": a.cfg.CooldownSeconds, "gap_seconds": a.cfg.GapSeconds, "news_keywords": a.cfg.News.Keywords, "news_notification_policy": newsNotificationPolicy(), "market_notification_policy": NotificationPlan{Mode: "alarm", SoundPolicy: "device_local_schedule", Vibration: true}, "default_device_schedule": map[string]any{"mode": "auto", "timezone": "device_local", "night_start": "22:00", "night_end": "08:00", "day_sound": false, "night_sound": true, "user_editable": true}, "moomoo": a.cfg.Moomoo})
	})
	mux.HandleFunc("GET /v1/user-rules", func(w http.ResponseWriter, r *http.Request) {
		set, status := a.rules.Snapshot()
		jsonOut(w, map[string]any{"rules": set.Rules, "updated_at_ms": set.UpdatedAt, "status": status, "symbols": a.rules.Symbols()})
	})
	mux.HandleFunc("PUT /v1/user-rules", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Rules []UserRule `json:"rules"`
		}
		dec := json.NewDecoder(io.LimitReader(r.Body, 64<<10))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&body); err != nil {
			http.Error(w, "invalid rules JSON", 400)
			return
		}
		a.mu.Lock()
		contracts := a.contracts
		a.mu.Unlock()
		missing := []string{}
		for _, rule := range body.Rules {
			if rule.Enabled && len(contracts) > 0 && contracts[rule.Symbol()] == "" {
				missing = append(missing, rule.Coin)
			}
		}
		if len(missing) > 0 {
			w.WriteHeader(422)
			jsonOut(w, map[string]any{"error": "币安没有这些 USDT 永续合约", "missing": missing})
			return
		}
		set, err := a.rules.Replace(body.Rules, time.Now().UnixMilli())
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		select {
		case a.resub <- struct{}{}:
		default:
		}
		log.Printf("user rules replaced count=%d symbols=%v", len(set.Rules), a.rules.Symbols())
		jsonOut(w, map[string]any{"rules": set.Rules, "updated_at_ms": set.UpdatedAt, "symbols": a.rules.Symbols()})
	})
	token := os.Getenv("PNALE_API_TOKEN")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token != "" && subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func main() {
	p := flag.String("config", "config.json", "config path")
	flag.Parse()
	b, err := os.ReadFile(*p)
	if err != nil {
		log.Fatal(err)
	}
	var c Config
	if err = json.Unmarshal(b, &c); err != nil {
		log.Fatal(err)
	}
	if err = validConfig(c); err != nil {
		log.Fatal(err)
	}
	host, _, err := net.SplitHostPort(c.Listen)
	if err != nil {
		log.Fatal(err)
	}
	if host != "127.0.0.1" && host != "::1" && os.Getenv("PNALE_API_TOKEN") == "" {
		log.Fatal("non-loopback listener requires PNALE_API_TOKEN and TLS reverse proxy")
	}
	st, err := OpenStore(c.DataDir)
	if err != nil {
		log.Fatal(err)
	}
	rules, err := OpenUserRules(c.DataDir, c.GapSeconds*1000)
	if err != nil {
		log.Fatal(err)
	}
	rules.Gaps = map[string]int64{}
	for _, s := range c.Symbols {
		if s.MaxTradeGapSeconds > 0 {
			rules.Gaps[s.Symbol] = s.MaxTradeGapSeconds * 1000
		}
	}
	a := &App{cfg: c, store: st, det: map[string]*Detector{}, news: map[string]NewsStatus{}, started: time.Now().UnixMilli(), contracts: map[string]string{}, rules: rules, resub: make(chan struct{}, 1)}
	old, _ := st.Snapshot(0, 5000)
	for _, s := range c.Symbols {
		gap := c.GapSeconds
		if s.MaxTradeGapSeconds > 0 {
			gap = s.MaxTradeGapSeconds
		}
		d := NewDetector(s, gap*1000, c.CooldownSeconds*1000)
		for _, e := range old {
			if e.Symbol != s.Symbol {
				continue
			}
			key := e.Kind
			if x, ok := e.Details["window_seconds"].(float64); ok && (e.Kind == "rapid_drop" || e.Kind == "rapid_rise") {
				key = fmt.Sprintf("%s:%d", e.Kind, int64(x)*1000)
			}
			d.Cooldown[key] = max(d.Cooldown[key], e.Observed)
		}
		a.det[s.Symbol] = d
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	a.startMoomoo(ctx)
	go a.stream(ctx)
	a.startNews(ctx)
	srv := &http.Server{Addr: c.Listen, Handler: a.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second}
	go func() {
		<-ctx.Done()
		ct, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(ct)
	}()
	log.Printf("Pnale monitor listening %s record_only", c.Listen)
	if err = srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func (a *App) restoreCooldown(d *Detector) {
	old, _ := a.store.Snapshot(0, 5000)
	for _, e := range old {
		if e.Symbol != d.Config.Symbol {
			continue
		}
		key := e.Kind
		if x, ok := e.Details["window_seconds"].(float64); ok && (e.Kind == "rapid_drop" || e.Kind == "rapid_rise") {
			key = fmt.Sprintf("%s:%d", e.Kind, int64(x)*1000)
		}
		d.Cooldown[key] = max(d.Cooldown[key], e.Observed)
	}
}
