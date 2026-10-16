package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func ruleEngine(t *testing.T) *UserRuleEngine {
	t.Helper()
	e, err := OpenUserRules(t.TempDir(), 15000)
	if err != nil {
		t.Fatal(err)
	}
	return e
}
func rule(kind string, value float64) UserRule {
	return UserRule{ID: "r1", Name: "测试", Coin: "BTC", Kind: kind, Value: value, Minutes: 1, Enabled: true, Duration: 60, Sound: true, Vibration: true, CooldownMinutes: 30}
}
func feed(e *UserRuleEngine, sec int, p float64) []Event {
	x := tick(sec, p)
	return e.Add("BTCUSDT", x, x.T)
}

func TestUserDropNeedsFullWindowThenDisarms(t *testing.T) {
	e := ruleEngine(t)
	if _, err := e.Replace([]UserRule{rule("drop", 1)}, 1); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 59; i++ {
		if ev := feed(e, i, 100); len(ev) != 0 {
			t.Fatal("fired before a full window", ev)
		}
	}
	if len(feed(e, 59, 98)) != 0 {
		t.Fatal("59 seconds is not a full minute")
	}
	ev := feed(e, 61, 98)
	if len(ev) != 1 || ev[0].Kind != "user_rule" || ev[0].Details["rule_id"] != "r1" || !ev[0].Notification.Sound {
		t.Fatalf("expected one user_rule alarm, got %+v", ev)
	}
	set, _ := e.Snapshot()
	if set.Rules[0].Enabled || set.Rules[0].LastFired == 0 {
		t.Fatal("one-shot rule must be disarmed and persisted", set.Rules[0])
	}
	reopened, err := OpenUserRules(e.path[:len(e.path)-len("/user-rules.json")], 15000)
	if err != nil || reopened.set.Rules[0].Enabled {
		t.Fatal("disarm must survive restart", err)
	}
	if len(feed(e, 62, 90)) != 0 {
		t.Fatal("disarmed rule fired again")
	}
}

func TestUserCrossingRequiresOppositeSide(t *testing.T) {
	e := ruleEngine(t)
	r := rule("above", 105)
	r.Repeat = true
	r.CooldownMinutes = 1
	e.Replace([]UserRule{r}, 1)
	if len(feed(e, 0, 106)) != 0 {
		t.Fatal("already above must not fire")
	}
	feed(e, 1, 104)
	if len(feed(e, 2, 105)) != 1 {
		t.Fatal("crossing up should fire")
	}
	feed(e, 3, 104)
	if len(feed(e, 4, 106)) != 0 {
		t.Fatal("cooldown must suppress a repeat")
	}
}

func TestUserRuleGapResetsWindow(t *testing.T) {
	e := ruleEngine(t)
	e.Replace([]UserRule{rule("drop", 1)}, 1)
	for i := 0; i < 30; i++ {
		feed(e, i, 100)
	}
	if len(feed(e, 61, 90)) != 0 {
		t.Fatal("a 31 second gap must reset, not bridge the window")
	}
}

func TestUserRuleValidation(t *testing.T) {
	bad := []UserRule{rule("drop", 100), rule("sideways", 1), {ID: "", Coin: "BTC", Kind: "drop", Value: 1, Minutes: 1, Duration: 60, CooldownMinutes: 1}}
	for _, r := range bad {
		if validUserRules([]UserRule{r}) == nil {
			t.Fatal("accepted invalid rule", r)
		}
	}
	dup := rule("drop", 1)
	if validUserRules([]UserRule{dup, dup}) == nil {
		t.Fatal("accepted duplicate IDs")
	}
}

func TestUserRulesAPI(t *testing.T) {
	a := testApp(t)
	a.contracts = map[string]string{"BTCUSDT": "PERPETUAL", "SOLUSDT": "PERPETUAL"}
	h := a.handler()
	put := func(rules []UserRule) *httptest.ResponseRecorder {
		b, _ := json.Marshal(map[string]any{"rules": rules})
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodPut, "/v1/user-rules", bytes.NewReader(b)))
		return w
	}
	sol := rule("drop", 2)
	sol.Coin = "SOL"
	if w := put([]UserRule{sol}); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	select {
	case <-a.resub:
	default:
		t.Fatal("new symbols must trigger a subscription")
	}
	if got := a.streams(); got[len(got)-1] != "solusdt@aggTrade" {
		t.Fatal("rule symbol missing from streams", got)
	}
	unknown := rule("drop", 2)
	unknown.Coin = "NOPE"
	if w := put([]UserRule{unknown}); w.Code != 422 {
		t.Fatal("unlisted coin must be rejected", w.Code)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v1/user-rules", nil))
	var got struct{ Rules []UserRule }
	json.Unmarshal(w.Body.Bytes(), &got)
	if len(got.Rules) != 1 || got.Rules[0].Coin != "SOL" {
		t.Fatal("rejected update must keep the previous rules", w.Body.String())
	}
}
