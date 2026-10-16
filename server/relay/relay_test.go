package relay

import (
	"context"
	"github.com/gorilla/websocket"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAllowlist(t *testing.T) {
	for _, raw := range []string{"/fapi/v1/order", "/fapi/v1/klines?symbol=BTCUSDT&interval=1m&limit=1000", "/fapi/v1/exchangeInfo?url=http://localhost", "/market/stream?streams=btcusdt@aggTrade", "/market/stream?streams=", "/fapi/v1/ticker/24hr?symbol=BTCUSDT&symbol=ETHUSDT"} {
		u, _ := url.Parse(raw)
		if validate(u.Path, u.Query()) == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}
func TestCachePreservesSourceTimestamp(t *testing.T) {
	var count atomic.Int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		io.WriteString(w, `{"symbol":"BTCUSDT","closeTime":1234}`)
	}))
	defer up.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h := New(ctx)
	h.REST = up.URL
	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", "/fapi/v1/ticker/24hr?symbol=BTCUSDT", nil))
		if w.Code != 200 || !strings.Contains(w.Body.String(), `"closeTime":1234`) {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	if count.Load() != 1 {
		t.Fatal("cache missed", count.Load())
	}
}
func TestNoArbitraryProxy(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h := New(ctx)
	for _, method := range []string{"POST", "PUT", "DELETE"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(method, "/fapi/v1/exchangeInfo", nil))
		if w.Code != 405 {
			t.Fatal(w.Code)
		}
	}
}
func TestWebSocket(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/market/stream" || r.URL.Query().Get("streams") != "btcusdt@ticker" {
			t.Error("wrong route", r.URL)
		}
		c, e := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if e != nil {
			return
		}
		defer c.Close()
		c.WriteMessage(websocket.TextMessage, []byte(`{"data":{"E":1234}}`))
		c.ReadMessage()
	}))
	defer up.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h := New(ctx)
	h.WS = "ws" + strings.TrimPrefix(up.URL, "http")
	s := httptest.NewServer(h)
	defer s.Close()
	c, _, e := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(s.URL, "http")+"/market/stream?streams=btcusdt@ticker", nil)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	_, body, e := c.ReadMessage()
	if e != nil || string(body) != `{"data":{"E":1234}}` {
		t.Fatal(string(body), e)
	}
}

func TestPanelProxyRequiresTokenAndAllowlist(t *testing.T) {
	var seen atomic.Value
	monitor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen.Store(r.Method + " " + r.URL.RequestURI() + " " + r.Header.Get("Authorization"))
		io.WriteString(w, `{"ok":true}`)
	}))
	defer monitor.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h := New(ctx)
	call := func(method, path, auth string) int {
		r := httptest.NewRequest(method, path, strings.NewReader(`{"rules":[]}`))
		if auth != "" {
			r.Header.Set("Authorization", auth)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	if call("GET", "/panel/v1/events", "Bearer s3cret") != 404 {
		t.Fatal("panel path must be off without a configured token")
	}
	h.Token, h.Monitor = "s3cret", monitor.URL
	if call("GET", "/panel/v1/events", "Bearer wrong") != 401 || call("GET", "/panel/v1/events", "") != 401 {
		t.Fatal("wrong or missing token must be rejected")
	}
	for _, bad := range [][2]string{{"GET", "/panel/v1/status"}, {"DELETE", "/panel/v1/user-rules"}, {"PUT", "/panel/v1/events"}} {
		if call(bad[0], bad[1], "Bearer s3cret") != 400 {
			t.Fatal("allowed", bad)
		}
	}
	if call("PUT", "/panel/v1/user-rules", "Bearer s3cret") != 200 || seen.Load() != "PUT /v1/user-rules Bearer s3cret" {
		t.Fatal("rule sync not forwarded", seen.Load())
	}
	if call("GET", "/panel/v1/events?kind=user_rule&after_ms=5", "Bearer s3cret") != 200 || seen.Load() != "GET /v1/events?kind=user_rule&after_ms=5 Bearer s3cret" {
		t.Fatal("event poll not forwarded", seen.Load())
	}
}
