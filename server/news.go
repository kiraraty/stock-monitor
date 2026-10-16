package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"github.com/gorilla/websocket"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"
)

type NewsConfig struct {
	JinseURL       string   `json:"jinse_url"`
	PollSeconds    int64    `json:"poll_seconds"`
	FinnhubEnabled bool     `json:"finnhub_enabled"`
	FinnhubURL     string   `json:"finnhub_url"`
	TreeEnabled    bool     `json:"tree_enabled"`
	TreeURL        string   `json:"tree_url"`
	Keywords       []string `json:"keywords"`
}
type NewsStatus struct {
	Enabled       bool   `json:"enabled"`
	LastSuccess   int64  `json:"last_success_ms"`
	LastPublished int64  `json:"last_published_ms"`
	Error         string `json:"error"`
	Tier          string `json:"tier"`
	Coverage      string `json:"coverage"`
	Received      int64  `json:"received"`
}
type NewsItem struct {
	ID          string
	Title       string
	Body        string
	URL         string
	Publisher   string
	Published   int64
	Importance  []string
	SourceGrade *int
}

func newsNotificationPolicy() map[string]any {
	return map[string]any{"version": "source_highest_v1", "mode": "highest_only", "delivery": "normal", "jinse_grade": 5, "ungraded_sources": "record_only", "keyword_match_can_notify": false, "alarm_sound": false}
}

func (a *App) newsStatus(source string, update func(*NewsStatus)) {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.news[source]
	update(&s)
	a.news[source] = s
}
func (a *App) ingestNews(source string, items []NewsItem, now int64) {
	sort.SliceStable(items, func(i, j int) bool { return items[i].Published < items[j].Published })
	for _, n := range items {
		a.newsStatus(source, func(s *NewsStatus) { s.LastPublished = max(s.LastPublished, n.Published) })
		if a.store.Contains(source + ":" + n.ID) {
			continue
		}
		if n.ID == "" || n.Title == "" || n.Published <= 0 {
			continue
		}
		reasons := append([]string{}, n.Importance...)
		text := strings.ToLower(n.Title + " " + n.Body)
		for _, kw := range a.cfg.News.Keywords {
			if strings.Contains(text, strings.ToLower(kw)) {
				reasons = append(reasons, "keyword:"+kw)
			}
		}
		kind := "news"
		if len(reasons) > 0 {
			kind = "important_news"
		}
		fresh := now-n.Published >= -5000 && now-n.Published <= 300000 && n.Published >= a.started
		// Jinse's own highest/red filter is exactly grade=5. Keywords and
		// "selected" labels do not establish a source's highest priority.
		highest := source == "jinse" && n.SourceGrade != nil && *n.SourceGrade == 5
		plan := &NotificationPlan{Mode: "record_only"}
		if fresh && highest {
			plan.Mode = "normal"
		}
		ev := Event{ID: source + ":" + n.ID, Kind: kind, Source: source, Observed: now, Published: n.Published, Title: clip(n.Title, 250), AlertEligible: fresh && highest, Notification: plan, Details: map[string]any{"url": n.URL, "publisher": n.Publisher, "importance_reasons": reasons, "source_grade": n.SourceGrade, "source_highest": highest, "notification_policy": "source_highest_v1", "latency_ms": now - n.Published, "backfill_or_delayed": !fresh, "content_hash": fmt.Sprintf("%x", sha256.Sum256([]byte(n.Title+"\n"+n.Body)))}}
		a.record(ev)
		a.newsStatus(source, func(s *NewsStatus) { s.LastPublished = max(s.LastPublished, n.Published); s.Received++ })
	}
}
func clip(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}
func (a *App) startNews(ctx context.Context) {
	a.news["jinse"] = NewsStatus{Enabled: a.cfg.News.JinseURL != "", Tier: "public_website_endpoint", Coverage: "latest page only; no complete historical coverage guarantee"}
	a.news["finnhub"] = NewsStatus{Enabled: a.cfg.News.FinnhubEnabled, Tier: "API key permissions unverified", Coverage: "latest general+crypto pages"}
	a.news["tree"] = NewsStatus{Enabled: a.cfg.News.TreeEnabled, Tier: "public (may be delayed)", Coverage: "live connection only; no automatic backfill"}
	if a.cfg.News.JinseURL != "" {
		go a.pollNews(ctx, "jinse", a.fetchJinse)
	}
	if a.cfg.News.FinnhubEnabled {
		go a.pollNews(ctx, "finnhub", a.fetchFinnhub)
	}
	if a.cfg.News.TreeEnabled {
		go a.treeLoop(ctx)
	}
}
func (a *App) pollNews(ctx context.Context, source string, fetch func(context.Context) ([]NewsItem, error)) {
	interval := time.Duration(max(int64(60), a.cfg.News.PollSeconds)) * time.Second
	delay := interval
	for ctx.Err() == nil {
		items, err := fetch(ctx)
		now := time.Now().UnixMilli()
		if err != nil {
			a.newsStatus(source, func(s *NewsStatus) { s.Error = err.Error() })
			delay = min(delay*2, 15*time.Minute)
		} else {
			a.ingestNews(source, items, now)
			a.newsStatus(source, func(s *NewsStatus) { s.Error = ""; s.LastSuccess = now })
			delay = interval
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
	}
}
func (a *App) fetchJinse(ctx context.Context) ([]NewsItem, error) {
	var feed struct {
		List []struct {
			Lives []struct {
				ID        int64  `json:"id"`
				Title     string `json:"content_prefix"`
				Content   string `json:"content"`
				Link      string `json:"link"`
				Time      int64  `json:"created_at"`
				Grade     *int   `json:"grade"`
				Attribute string `json:"attribute"`
			} `json:"lives"`
		} `json:"list"`
	}
	if err := getJSON(ctx, a.cfg.News.JinseURL, nil, &feed); err != nil {
		return nil, err
	}
	if feed.List == nil {
		return nil, fmt.Errorf("jinse missing list schema")
	}
	items := []NewsItem{}
	for _, group := range feed.List {
		for _, n := range group.Lives {
			reasons := []string{}
			if n.Grade != nil && *n.Grade == 5 {
				reasons = append(reasons, fmt.Sprintf("source_grade:%d", *n.Grade))
			}
			if strings.Contains(n.Attribute, "精选") {
				reasons = append(reasons, "source_selected")
			}
			title := n.Title
			if title == "" {
				title = clip(n.Content, 160)
			}
			link := fmt.Sprintf("https://jinse.com.cn/lives/%d.html", n.ID)
			items = append(items, NewsItem{ID: fmt.Sprint(n.ID), Title: title, Body: n.Content, URL: link, Publisher: "金色财经", Published: n.Time * 1000, Importance: reasons, SourceGrade: n.Grade})
		}
	}
	return items, nil
}
func (a *App) fetchFinnhub(ctx context.Context) ([]NewsItem, error) {
	key := os.Getenv("FINNHUB_API_KEY")
	if key == "" {
		return nil, fmt.Errorf("FINNHUB_API_KEY missing")
	}
	items := []NewsItem{}
	for _, category := range []string{"general", "crypto"} {
		u, err := url.Parse(a.cfg.News.FinnhubURL)
		if err != nil {
			return nil, err
		}
		q := u.Query()
		q.Set("category", category)
		u.RawQuery = q.Encode()
		var feed []struct {
			ID       int64  `json:"id"`
			Headline string `json:"headline"`
			URL      string `json:"url"`
			Source   string `json:"source"`
			Datetime int64  `json:"datetime"`
		}
		if err := getJSON(ctx, u.String(), http.Header{"X-Finnhub-Token": []string{key}}, &feed); err != nil {
			return nil, err
		}
		for _, n := range feed {
			items = append(items, NewsItem{ID: fmt.Sprint(n.ID), Title: n.Headline, URL: n.URL, Publisher: n.Source, Published: n.Datetime * 1000})
		}
	}
	return items, nil
}
func parseTree(b []byte) (NewsItem, bool) {
	var m map[string]json.RawMessage
	if json.Unmarshal(b, &m) != nil {
		return NewsItem{}, false
	}
	str := func(k string) string { var s string; json.Unmarshal(m[k], &s); return s }
	n := NewsItem{ID: str("_id"), Title: str("title"), Body: str("body"), URL: str("link"), Publisher: str("source")}
	if n.ID == "" && len(m["_id"]) > 0 {
		var num json.Number
		if json.Unmarshal(m["_id"], &num) == nil {
			n.ID = num.String()
		}
	}
	json.Unmarshal(m["time"], &n.Published)
	if n.URL == "" {
		n.URL = str("url")
	}
	if n.Publisher == "" {
		n.Publisher = "Tree: social/source unspecified"
	}
	if n.Body != "" {
		n.Title = clip(n.Title+" · "+n.Body, 250)
	}
	return n, n.ID != "" && n.Title != "" && n.Published > 0
}
func (a *App) treeLoop(ctx context.Context) {
	delay := time.Second
	for ctx.Err() == nil {
		err := a.treeConnection(ctx)
		if ctx.Err() != nil {
			return
		}
		a.newsStatus("tree", func(s *NewsStatus) { s.Error = fmt.Sprint(err) })
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		delay = min(delay*2, time.Minute)
	}
}
func (a *App) treeConnection(ctx context.Context) error {
	dial := websocket.Dialer{Proxy: http.ProxyFromEnvironment, HandshakeTimeout: 15 * time.Second}
	c, _, err := dial.DialContext(ctx, a.cfg.News.TreeURL, nil)
	if err != nil {
		return fmt.Errorf("tree websocket connection failed")
	}
	defer c.Close()
	c.SetReadLimit(2 << 20)
	key := os.Getenv("TREE_API_KEY")
	if key != "" {
		if err = c.WriteMessage(websocket.TextMessage, []byte("login "+key)); err != nil {
			return fmt.Errorf("tree login send failed")
		}
		a.newsStatus("tree", func(s *NewsStatus) { s.Tier = "key supplied; entitlement not independently verified" })
	}
	mark := func() {
		a.newsStatus("tree", func(s *NewsStatus) { s.Error = ""; s.LastSuccess = time.Now().UnixMilli() })
	}
	mark()
	c.SetPongHandler(func(string) error { mark(); return c.SetReadDeadline(time.Now().Add(90 * time.Second)) })
	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ctx.Done():
				c.Close()
				return
			case <-ticker.C:
				if c.WriteControl(websocket.PingMessage, []byte("pnale"), time.Now().Add(5*time.Second)) != nil {
					c.Close()
					return
				}
			}
		}
	}()
	for {
		c.SetReadDeadline(time.Now().Add(90 * time.Second))
		_, b, err := c.ReadMessage()
		if err != nil {
			return fmt.Errorf("tree websocket disconnected")
		}
		mark()
		if n, ok := parseTree(b); ok {
			a.ingestNews("tree", []NewsItem{n}, time.Now().UnixMilli())
		} else {
			var m map[string]any
			if json.Unmarshal(b, &m) == nil {
				if _, ok := m["error"]; ok {
					return fmt.Errorf("tree returned error control message")
				}
			}
		}
	}
}
