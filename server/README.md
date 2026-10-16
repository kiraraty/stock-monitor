# Panel market monitor

Go monitoring daemon deployed on the authorized Singapore host. It receives public Binance USD-M aggregate trades, public Jinse news, the public Tree News WebSocket, and Moomoo US stock ticks through an isolated Python SDK bridge. All events are **record only**. No order APIs, trading credentials, outbound chat messages, or phone alarm delivery are used.

## Deployment and operation

- Unit: `pnale-monitor.service`, runs as non-root `pnale`.
- Release directory: `/opt/pnale-monitor/releases/`; `current` is the active release symlink.
- Configuration: `/etc/pnale-monitor/config.json`.
- Event files: `/var/lib/pnale-monitor/events-YYYY-MM-DD.jsonl`, appended and fsynced; recent 5,000 records loaded into the API's memory window. All daily files are retained; monitor disk usage. Invalid persisted records fail startup explicitly.
- API binds `127.0.0.1:8797`. It is reachable locally or through SSH forwarding, not directly from the phone yet.
- Optional credentials: `/etc/pnale-monitor/secrets.env`, root-readable file; keys `TREE_API_KEY`, `FINNHUB_API_KEY`, `PNALE_API_TOKEN`. No keys are included in this checkout. Finnhub additionally needs `news.finnhub_enabled=true`.
- Resource controls: CPU 40% of one core; memory high 240 MiB, maximum 320 MiB, covering Go and the Python bridge together.

```sh
systemctl status pnale-monitor
journalctl -u pnale-monitor -n 50 --no-pager
curl -sS http://127.0.0.1:8797/v1/status
curl -sS 'http://127.0.0.1:8797/v1/events?limit=20'
```

Access from your Mac:

```sh
ssh -p 2222 -L 8797:127.0.0.1:8797 root@43.156.153.114
```

Then open `http://127.0.0.1:8797/v1/status` or use curl. Keep the tunnel open.

After editing configuration, restart only `pnale-monitor`. Previous configuration and previous release path are saved by `deploy/install.sh`. To roll back, restore the matching saved configuration, point `current` to the preceding release, and restart this unit. Existing OpenD, strategy processes, FRP and other services are not modified.

## Rules

`symbols` and `moomoo.symbols` define the monitored population. `thresholds` correspond, in order, to **60, 180, 300 seconds**. These are initial observation settings, not calibrated trading signals or evidence of profitability.

| Instrument | 1 minute | 3 minutes | 5 minutes | Wick excursion |
| --- | ---: | ---: | ---: | ---: |
| BTCUSDT | 1% | 1.5% | 2% | 1.5% |
| ETHUSDT | 1.2% | 1.8% | 2.5% | 2% |
| XAUUSDT | 0.5% | 0.8% | 1.2% | 0.8% |
| CLUSDT, BZUSDT | 1% | 1.5% | 2% | 1.5% |
| Moomoo US watchlist | 1.5% | 2.5% | 3.5% | 2.5% |

- `rapid_drop` / `rapid_rise`: last trade compared with the latest observed trade at or before the window's start. A complete elapsed observation window is required. Tests ensure that the 24-hour change is never substituted for this return.
- `down_wick_rebound`: over five minutes, an observed peak occurs before a trough; the decline exceeds the configured excursion and the current trade recovers at least 60% of that decline. `up_wick_reversal` is the symmetric condition. These events confirm recovery **after it happens**; they do not predict the bottom/top. Wick checks run at most once per second and retain ordered trade extrema between evaluations.
- A 300-second cooldown applies to each symbol, direction and window. Each window has its own event ID. Recently persisted events restore cooldowns after restart. No claim is made that every natural anomaly has been detected.
- Old (>15 seconds delayed), future (>5 seconds), duplicate, invalid and out-of-order ticks are excluded.
- Explicit WebSocket disconnects reset observation history. No artificial backfill is added. `max_trade_gap_seconds` bounds how much time without a trade is accepted inside a symbol's observed history: BTC/ETH 15 seconds, gold/Brent 90 seconds, WTI 60 seconds. This distinguishes ordinary sparse trading from a frozen tick; it is **not proof that every exchange trade was captured**. An excessive gap resets history and records `data_gap`.
- `quiet` means no trade in 15 seconds but the configured maximum gap is not yet exceeded; the last price and its age remain explicit. Stale or warming sources make `/healthz` return HTTP 503.
- Buffers have a 200,000 tick/symbol cap; overflow produces `buffer_reset` and suppresses detection until fresh history accumulates.
- Commodity quotes are Binance's **tradfi perpetual contracts**, not a direct COMEX/NYMEX spot/futures feed. No volume-spike confirmation, order-book attribution, or automatic trading is implemented.

## Moomoo

Existing OpenD listens on localhost 11111. The independent bridge uses a dedicated `/opt/pnale-monitor/venv`; it does not import or modify the stock analysis pipeline. It only opens `OpenQuoteContext`.

Initial pool: SPY, QQQ, AAPL, NVDA, TSLA, MSFT, AMD, META. One `TICKER` subscription per instrument consumes eight quota units. The bridge accepts up to 100 configured US symbols; OpenD still enforces the account's actual quota and other active subscriptions. Enlarging the pool requires observing memory, callback lag and discarded ticks, not just successful subscription.

Realtime ordinary prints are accepted; cached/reconnect replay, delayed, auction and special-condition prints are excluded. The current whitelist is `AUTO_MATCH`, `INTER_AUTO_MATCH`, `ODD_LOT`. US Eastern dates and premarket/regular/after-hours/overnight segments get separate histories. Market state and OpenD login checks repeat through the bridge heartbeat; quote permissions are queried when the bridge connects. At the initial Sunday deployment, LV3 permission and subscriptions were verified; active-session ticks, their exact venue coverage and latency remain pending the next market session.

## News

All sources retain source IDs, provider timestamps, first observed timestamps, headline, origin URL, content hash and importance reasons. IDs deduplicate per provider; cross-provider semantic story merging is not implemented.

- **Jinse:** current public website endpoint, latest page (100 entries), every 60 seconds with backoff on errors. Only source grade **exactly 5** (the website highest/red filter) qualifies for notification. A selected marker or keyword match can label a record important for inspection but cannot qualify it for notification. It is a public website endpoint without a published stability guarantee, not a contracted commercial API.
- **Tree News:** public main WebSocket `wss://news.treeofalpha.com/ws`, application heartbeat, reconnect. Public-tier delay/limited sources remain applicable. An optional key uses the documented `login <key>` message; subscription entitlement is not independently verified by this implementation. No licensed Bloomberg/Reuters coverage or millisecond arrival guarantee is claimed. The public stream has received at least one live message during validation.
- **Finnhub:** enabled with the server's protected API key. Both `general` and `crypto` REST news requests returned HTTP 200; the daemon fetched and persisted 189 initial entries on activation. Polls every 60 seconds; startup history stays ineligible for alerts. Subscription tier, maximum quota and paid news/sentiment entitlements remain unverified. Company news sentiment is a separate endpoint and is not silently attached to crypto headlines. See [activation evidence](validation/finnhub-activation.json).
- News delivery policy is **highest source grade only, ordinary notification, no alarm sound**. Tree and Finnhub do not provide a verified highest-priority field in the currently consumed payloads, so their entries remain record-only. Old persisted news without this policy is exposed as ineligible in the API; original daily files remain unchanged.
- Market events carry `sound_policy=device_local_schedule`: the phone resolves sound against its own local-time settings. The default is automatic, 22:00–08:00 with sound plus vibration, daytime vibration only; the Android settings allow changing the interval or choosing manual vibration/ring modes. The server does not assume the phone is in Singapore. This is a delivery contract; the phone integration is still pending.
- Historical startup entries and news older than five minutes are stored with `alert_eligible=false`; a new matching item must also be published after the current process started. This prevents startup backlog from becoming a loud alarm later. The daemon currently emits no external notification even when `alert_eligible=true`.
- Jinse/Finnhub latest-page polling and Tree reconnects can have coverage gaps. Full historical capture or complete news coverage is not claimed.

## Phone-defined alarm rules

The Android app (0.10.0+) can hand its alarm rules to the server: 警报 → 服务器监测, enter the token, enable. The server stores them in `/var/lib/pnale-monitor/user-rules.json` (atomic replace), evaluates them on Binance aggregate trades with the same semantics as the phone (full observed 1–60 minute window, crossings need a prior opposite side, one-shot rules disarmed and persisted before the event is published, cooldown for repeats), and records `kind=user_rule` events. New rule symbols are added with `SUBSCRIBE` on the live socket; existing histories are kept.

- `GET /v1/user-rules`, `PUT /v1/user-rules` (`{"rules":[...]}`, same fields as the phone's rule JSON; unknown fields and coins Binance does not list are rejected).
- `GET /v1/events?kind=user_rule&after_ms=…` for the phone's 5-second poll.
- The public relay exposes only these two paths at `/panel/v1/...`, and only when `PNALE_API_TOKEN` is set (relay loads `/etc/pnale-monitor/secrets.env`). The monitor API also requires the token now: add `-H "Authorization: Bearer $PNALE_API_TOKEN"` to the curl commands above.
- Delivery is still phone-pull: the phone must keep tracking running. Events older than 2 minutes when the phone fetches them are recorded as missed, not rung.

## API

- `GET /healthz`: 200 only when required enabled sources are ready; 503 during warmup/staleness/errors. Closed US sessions are explicitly `market_closed`.
- `GET /v1/status`: source states, current prices/timestamps, coverage durations, heartbeat, reconnects, permissions, storage errors; always JSON for inspection.
- `GET /v1/rules`: current configured thresholds/windows and population.
- `GET /v1/events?limit=100&after_ms=<timestamp>`: newest-first recent records. Up to 1,000 records per response; only the last 5,000 in memory. This is a bounded inspection API, not an acknowledged durable delivery cursor. Read daily files for complete stored history.

## Build and tests

```sh
go test -race ./...
go vet ./...
python3 -m unittest test_moomoo_bridge -v
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o pnale-monitor .
```

The SDK version used on the server is pinned in `requirements.lock`. The Go dependency is pinned in `go.mod`/`go.sum`. The Android integration, server management UI and push-to-phone path are planned for a later phase.

## Primary documentation

- [Binance aggregate trade stream](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-streams/market)
- [Moomoo subscription quotas](https://openapi.moomoo.com/moomoo-api-doc/intro/authority.html)
- [Moomoo tick callback](https://openapi.moomoo.com/moomoo-api-doc/en/quote/update-ticker.html)
- [Tree WebSocket tiers](https://docs.treeofalpha.com/websockets.md), [connection](https://docs.treeofalpha.com/websockets/python-connection.md), [payload](https://docs.treeofalpha.com/websockets/response.md)
- [Finnhub API schema](https://github.com/Finnhub-Stock-API/finnhub-go/blob/master/api/openapi.yaml)
