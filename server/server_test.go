package main

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testApp(t *testing.T) *App {
	t.Helper()
	s, e := OpenStore(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	return &App{cfg: Config{GapSeconds: 15, News: NewsConfig{PollSeconds: 60, Keywords: []string{"FED", "被盗"}}}, store: s, det: map[string]*Detector{"BTCUSDT": detector()}, news: map[string]NewsStatus{}, contracts: map[string]string{}, started: 1700000000000, rules: &UserRuleEngine{path: t.TempDir() + "/user-rules.json", series: map[string]*ruleSeries{}, monitors: map[string]*ruleMonitor{}, status: map[string]string{}, GapMS: 15000}, resub: make(chan struct{}, 1)}
}
func TestStoreDurabilityDedupAndPartialFailure(t *testing.T) {
	dir := t.TempDir()
	s, e := OpenStore(dir)
	if e != nil {
		t.Fatal(e)
	}
	ev := Event{ID: "x", Kind: "test", Observed: 1700000000000}
	s.Add(ev)
	s.Add(ev)
	s2, e := OpenStore(dir)
	if e != nil {
		t.Fatal(e)
	}
	x, _ := s2.Snapshot(0, 50)
	if len(x) != 1 {
		t.Fatal(x)
	}
	if e = os.WriteFile(filepath.Join(dir, "events-9999-01-01.jsonl"), []byte("invalid\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = OpenStore(dir); e == nil {
		t.Fatal("corrupt history not rejected")
	}
}
func TestNewsHistoricalDoesNotAlertAndPersistsOnce(t *testing.T) {
	a := testApp(t)
	now := a.started + 60000
	item := NewsItem{ID: "1", Title: "FED cuts rates", Published: a.started - 60000}
	a.ingestNews("jinse", []NewsItem{item}, now)
	a.ingestNews("jinse", []NewsItem{item}, now+1)
	ev, _ := a.store.Snapshot(0, 10)
	if len(ev) != 1 || ev[0].AlertEligible || ev[0].Kind != "important_news" {
		t.Fatal(ev)
	}
	item.ID = "2"
	item.Published = now
	grade := 5
	item.SourceGrade = &grade
	a.ingestNews("jinse", []NewsItem{item}, now)
	ev, _ = a.store.Snapshot(0, 10)
	if !ev[0].AlertEligible {
		t.Fatal(ev)
	}
}

func TestHighestNewsOnlyAndNormalDelivery(t *testing.T) {
	for _, tc := range []struct {
		source   string
		grade    int
		expected bool
	}{
		{"jinse", 5, true}, {"jinse", 4, false}, {"jinse", 6, false}, {"jinse", 0, false}, {"tree", 5, false}, {"finnhub", 5, false},
	} {
		t.Run(fmt.Sprintf("%s-%d", tc.source, tc.grade), func(t *testing.T) {
			a := testApp(t)
			n := NewsItem{ID: "x", Title: "FED 被盗", Published: a.started + 1000, Importance: []string{"source_selected"}}
			if tc.grade != 0 {
				n.SourceGrade = &tc.grade
			}
			a.ingestNews(tc.source, []NewsItem{n}, a.started+1000)
			ev, _ := a.store.Snapshot(0, 10)
			if ev[0].AlertEligible != tc.expected || ev[0].Notification.Sound || ev[0].Notification.Vibration {
				t.Fatal(ev[0])
			}
			if tc.expected && ev[0].Notification.Mode != "normal" || !tc.expected && ev[0].Notification.Mode != "record_only" {
				t.Fatal(ev[0])
			}
		})
	}
}

func TestLegacyNewsCannotReplayAsNotification(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	s.Add(Event{ID: "legacy", Kind: "important_news", AlertEligible: true, Observed: 1700000000000})
	s, err = OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	ev, _ := s.Snapshot(0, 10)
	if ev[0].AlertEligible || ev[0].Notification.Mode != "record_only" {
		t.Fatal(ev[0])
	}
}
func TestTreeParserUsesBodyAndSource(t *testing.T) {
	n, ok := parseTree([]byte(`{"_id":"123","title":"Macro account","body":"FED cuts rates","time":1700000000000,"link":"https://example.org"}`))
	if !ok || !strings.Contains(n.Title, "FED") || n.Published != 1700000000000 {
		t.Fatal(n)
	}
	if _, ok = parseTree([]byte(`{"success":true}`)); ok {
		t.Fatal("control message parsed as news")
	}
}
func TestFinnhubAuthorizationAndBothCategories(t *testing.T) {
	a := testApp(t)
	t.Setenv("FINNHUB_API_KEY", "fixture-key")
	cats := map[string]bool{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Finnhub-Token") != "fixture-key" {
			t.Error("missing auth")
		}
		cats[r.URL.Query().Get("category")] = true
		w.Write([]byte(`[{"id":1,"headline":"FED","datetime":1700000000,"source":"fixture","url":"https://example.org"}]`))
	}))
	defer srv.Close()
	a.cfg.News.FinnhubURL = srv.URL
	x, err := a.fetchFinnhub(context.Background())
	if err != nil || len(x) != 2 || !cats["crypto"] || !cats["general"] {
		t.Fatal(x, err, cats)
	}
}
func TestJinseSchemaAndGrades(t *testing.T) {
	a := testApp(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"list":[{"lives":[{"id":1,"content_prefix":"重要","content":"content","created_at":1700000000,"grade":5,"attribute":"精选"}]}]}`))
	}))
	defer srv.Close()
	a.cfg.News.JinseURL = srv.URL
	x, err := a.fetchJinse(context.Background())
	if err != nil || len(x) != 1 || len(x[0].Importance) != 2 {
		t.Fatal(x, err)
	}
}
func TestHealthAndAuth(t *testing.T) {
	a := testApp(t)
	t.Setenv("PNALE_API_TOKEN", "secret-fixture")
	h := a.handler()
	r := httptest.NewRequest("GET", "/healthz", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	r.Header.Set("Authorization", "Bearer secret-fixture")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 503 {
		t.Fatal(w.Code)
	}
	a.connected = true
	d := a.det["BTCUSDT"]
	d.Last = tick(0, 100)
	d.Last.T = time.Now().UnixMilli()
	d.Started = d.Last.T - 301000
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	d.Last.T -= 16000
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 503 {
		t.Fatal("stale reported healthy")
	}
}
func TestRealWSDecodeAndContextCancellation(t *testing.T) {
	a := testApp(t)
	up := websocket.Upgrader{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, e := up.Upgrade(w, r, nil)
		if e != nil {
			return
		}
		defer c.Close()
		c.WriteJSON(map[string]any{"data": map[string]any{"e": "aggTrade", "E": time.Now().UnixMilli(), "s": "BTCUSDT", "T": time.Now().UnixMilli(), "a": 1, "p": "100.0"}})
		for {
			if _, _, e = c.ReadMessage(); e != nil {
				return
			}
		}
	}))
	defer srv.Close()
	a.cfg.WSURL = "ws" + strings.TrimPrefix(srv.URL, "http")
	a.cfg.Symbols = []SymbolConfig{a.det["BTCUSDT"].Config}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- a.connection(ctx) }()
	defer cancel()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		v, _ := a.health()
		b, _ := json.Marshal(v)
		if strings.Contains(string(b), `"price":100`) {
			cancel()
			select {
			case <-done:
				return
			case <-time.After(time.Second):
				t.Fatal("cancel failed")
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("did not decode real WS message")
}
func TestMoomooSessionBoundaryAndClosedHealth(t *testing.T) {
	a := testApp(t)
	d := NewDetector(SymbolConfig{Symbol: "US.NVDA", Thresholds: []float64{1, 2, 3}, WickPercent: 2}, 15000, 300000)
	d.Source = "moomoo_us_ticker"
	a.det["US.NVDA"] = d
	a.moomoo = MoomooStatus{Enabled: true, States: map[string]string{}, Session: map[string]string{}}
	now := time.Now().UnixMilli()
	b, _ := json.Marshal(map[string]any{"type": "state", "time_ms": now, "permission": "LV3", "quota": 100, "states": map[string]string{"US.NVDA": "AFTER_HOURS_END"}})
	a.moomooMessage(b)
	v, _ := a.health()
	if v["symbols"].(map[string]any)["US.NVDA"].(map[string]any)["state"] != "market_closed" {
		t.Fatal(v)
	}
	for i, session := range []string{"regular", "after"} {
		b, _ = json.Marshal(map[string]any{"type": "tick", "symbol": "US.NVDA", "t": now + int64(i), "p": 100 - i*20, "id": i + 1, "session": session})
		if e := a.moomooMessage(b); e != nil {
			t.Fatal(e)
		}
	}
	if len(d.Ticks) != 1 || d.Started != now+1 {
		t.Fatal("cross session history retained")
	}
}
