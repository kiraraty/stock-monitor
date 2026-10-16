// Package relay exposes only allowlisted, public Binance market data for the Panel app.
package relay

import (
	"context"
	"crypto/subtle"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var symbol = regexp.MustCompile(`^[A-Z0-9]{1,30}USDT$`)
var stream = regexp.MustCompile(`^[a-z0-9]{1,30}usdt@(ticker|kline_(1m|5m|15m|1h))$`)

type cached struct {
	body []byte
	at   time.Time
}
type Handler struct {
	REST, WS string
	// Token and Monitor enable the authenticated /panel/ path to the local monitor; empty Token disables it.
	Token, Monitor string
	client   *http.Client
	mu       sync.Mutex
	cache    map[string]cached
	slots    chan struct{}
	requests chan struct{}
	budget   chan struct{}
}

func New(ctx context.Context) *Handler {
	h := &Handler{REST: "https://fapi.binance.com", WS: "wss://fstream.binance.com", client: &http.Client{Timeout: 12 * time.Second},
		cache: make(map[string]cached), slots: make(chan struct{}, 16), requests: make(chan struct{}, 12), budget: make(chan struct{}, 40)}
	for i := 0; i < cap(h.budget); i++ {
		h.budget <- struct{}{}
	}
	go func() {
		t := time.NewTicker(100 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				select {
				case h.budget <- struct{}{}:
				default:
				}
			}
		}
	}()
	return h
}

func validate(path string, q url.Values) error {
	allowed := map[string]bool{}
	switch path {
	case "/fapi/v1/exchangeInfo":
	case "/fapi/v1/ticker/24hr":
		allowed["symbol"] = true
		if v := q.Get("symbol"); v != "" && !symbol.MatchString(v) {
			return fmt.Errorf("invalid symbol")
		}
	case "/fapi/v1/klines":
		allowed = map[string]bool{"symbol": true, "interval": true, "limit": true}
		if !symbol.MatchString(q.Get("symbol")) || !map[string]bool{"1m": true, "5m": true, "15m": true, "1h": true}[q.Get("interval")] || q.Get("limit") != "60" {
			return fmt.Errorf("invalid candle request")
		}
	case "/market/stream":
		allowed["streams"] = true
		streams := strings.Split(q.Get("streams"), "/")
		if len(streams) > 31 {
			return fmt.Errorf("too many streams")
		}
		for _, s := range streams {
			if !stream.MatchString(s) {
				return fmt.Errorf("invalid stream")
			}
		}
	default:
		return fmt.Errorf("path not allowed")
	}
	for k, v := range q {
		if !allowed[k] || len(v) != 1 {
			return fmt.Errorf("parameter not allowed")
		}
	}
	return nil
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if strings.HasPrefix(r.URL.Path, "/panel/") {
		h.panel(w, r)
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", 405)
		return
	}
	if r.URL.Path == "/healthz" {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"ok":true,"service":"panel-public-market-relay"}`)
		return
	}
	if len(r.URL.RawQuery) > 2048 || validate(r.URL.Path, r.URL.Query()) != nil {
		http.Error(w, "market request not allowed", 400)
		return
	}
	select {
	case <-h.budget:
	default:
		w.Header().Set("Retry-After", "5")
		http.Error(w, "rate limit", 429)
		return
	}
	if r.URL.Path == "/market/stream" {
		h.websocket(w, r)
		return
	}
	select {
	case h.requests <- struct{}{}:
		defer func() { <-h.requests }()
	default:
		http.Error(w, "busy", 503)
		return
	}
	key := r.URL.Path + "?" + r.URL.Query().Encode()
	// Serialize cache misses to prevent a thundering herd against Binance's IP quota.
	h.mu.Lock()
	defer h.mu.Unlock()
	ttl := 2 * time.Second
	if r.URL.Path == "/fapi/v1/exchangeInfo" {
		ttl = 10 * time.Minute
	}
	entry, ok := h.cache[key]
	if !ok || time.Since(entry.at) > ttl {
		req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, h.REST+key, nil)
		if err != nil {
			http.Error(w, "invalid request", 400)
			return
		}
		req.Header.Set("User-Agent", "PanelMarketRelay/0.8")
		response, err := h.client.Do(req)
		if err != nil {
			http.Error(w, "upstream unavailable", 502)
			return
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			http.Error(w, fmt.Sprintf("upstream HTTP %d", response.StatusCode), 502)
			return
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 8*1024*1024+1))
		if err != nil || len(body) > 8*1024*1024 {
			http.Error(w, "invalid upstream body", 502)
			return
		}
		entry = cached{body, time.Now()}
		if len(h.cache) >= 256 {
			for k, e := range h.cache {
				if time.Since(e.at) > ttl {
					delete(h.cache, k)
				}
			}
			if len(h.cache) >= 256 {
				clear(h.cache)
			}
		}
		h.cache[key] = entry
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(entry.body)
}

func (h *Handler) websocket(w http.ResponseWriter, r *http.Request) {
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		http.Error(w, "stream capacity", 503)
		return
	}
	upstream, _, err := (&websocket.Dialer{HandshakeTimeout: 10 * time.Second}).DialContext(r.Context(), h.WS+"/market/stream?"+r.URL.Query().Encode(), nil)
	if err != nil {
		http.Error(w, "upstream stream unavailable", 502)
		return
	}
	defer upstream.Close()
	client, err := (&websocket.Upgrader{HandshakeTimeout: 10 * time.Second}).Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer client.Close()
	upstream.SetReadLimit(1024 * 1024)
	client.SetReadLimit(1024)
	// No client application messages are forwarded: this is never a trading/API proxy.
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer upstream.Close()
		client.SetReadDeadline(time.Now().Add(70 * time.Second))
		client.SetPongHandler(func(string) error { return client.SetReadDeadline(time.Now().Add(70 * time.Second)) })
		for {
			_, _, e := client.ReadMessage()
			if e != nil {
				return
			}
			return
		}
	}()
	go func() {
		ticker := time.NewTicker(20 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				if client.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)) != nil {
					client.Close()
					return
				}
			}
		}
	}()
	for {
		kind, body, err := upstream.ReadMessage()
		if err != nil {
			return
		}
		if kind != websocket.TextMessage {
			continue
		}
		client.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if client.WriteMessage(kind, body) != nil {
			return
		}
	}
}

// panel forwards only the phone's rule sync and alarm event reads to the loopback monitor.
func (h *Handler) panel(w http.ResponseWriter, r *http.Request) {
	if h.Token == "" || h.Monitor == "" {
		http.NotFound(w, r)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+h.Token)) != 1 {
		http.Error(w, "unauthorized", 401)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/panel")
	allowed := (path == "/v1/user-rules" && (r.Method == http.MethodGet || r.Method == http.MethodPut)) ||
		(path == "/v1/events" && r.Method == http.MethodGet)
	if !allowed || len(r.URL.RawQuery) > 256 {
		http.Error(w, "panel request not allowed", 400)
		return
	}
	select {
	case <-h.budget:
	default:
		w.Header().Set("Retry-After", "5")
		http.Error(w, "rate limit", 429)
		return
	}
	target := h.Monitor + path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, io.LimitReader(r.Body, 64<<10))
	if err != nil {
		http.Error(w, "invalid request", 400)
		return
	}
	req.Header.Set("Authorization", "Bearer "+h.Token)
	req.Header.Set("Content-Type", "application/json")
	response, err := h.client.Do(req)
	if err != nil {
		http.Error(w, "monitor unavailable", 502)
		return
	}
	defer response.Body.Close()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(response.StatusCode)
	io.Copy(w, io.LimitReader(response.Body, 4<<20))
}
