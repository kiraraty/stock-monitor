# Panel public market relay — 0.8.0

Independent Go service on the existing Singapore host. It does not restart or expose the private monitor API, OpenD, news keys, event store, or trading services. The Android app uses it as an automatic fallback or an explicitly selected route.

- Public HTTPS/WSS: `43.156.153.114:443`; `pnale-relay.service`, non-root `pnale`, CAP_NET_BIND_SERVICE only. Release `/opt/pnale-relay/releases/20260921-v08`, symlink `/opt/pnale-relay/current`.
- Allowlisted GET routes: `/fapi/v1/exchangeInfo`, `/fapi/v1/ticker/24hr`, `/fapi/v1/klines`, `/market/stream`, `/healthz`. Fixed Binance upstreams; validated symbols, intervals, limit=60, and at most 31 ticker/kline streams. No arbitrary destinations, authenticated/account paths, orders or client WS messages are forwarded.
- HTTP cache 2 seconds (catalog 10 minutes). Source bodies/timestamps remain unchanged. Global 10 requests/second with burst 40; 16 WS connections, 12 in-flight REST requests, bounded cache/body/header sizes, upstream timeouts. No stored phone/user configuration.
- TLS private key `/etc/pnale-relay/server.key`, root:pnale 0640. Dedicated IP-SAN certificate, SHA256 `57:2F:95:6D:BE:97:4E:40:35:D3:70:25:EB:7F:EF:04:52:A2:B2:B1:62:92:FB:D9:AD:C4:FE:AC:58:31:6E:73`, expires **2029-09-20 11:34:54 UTC**. Public certificate is bundled at `android/app/src/main/res/raw/panel_relay.crt`; the relay client trusts only this certificate and retains hostname/IP verification. Direct Binance uses the platform trust store. No trust-all verifier or cleartext fallback.
- This private certificate is not a browser-trusted public CA certificate. Maintenance must update the APK trust material before changing/expiring the server certificate; no automatic renewal is configured. The private key must never be shipped in the APK or copied into the repository.

```sh
cd server
go test -race ./relay
go vet ./relay ./cmd/relay
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o pnale-relay ./cmd/relay
# Install the tested binary into a new /opt/pnale-relay/releases/<version>,
# update only the pnale-relay/current symlink, then restart pnale-relay.
# Keep the certificate/key unchanged unless coordinating an app update.
```

Current deployed binary SHA256: `bc3d36def2ebb8c5331339457007cb5a499ea9516c6181107a35c279b2fec5bf`.
Rollback: repoint the relay symlink to a known prior release, or stop `pnale-relay` and select direct mode in the app. Never restart the monitor merely to roll back this relay.

Validation: [runtime and identical hash](../validation/v08/relay-runtime.txt), [Go race tests](../validation/v08/relay-tests.txt), [Android transport failures and recovery](../../android/validation/v08/network.txt). The emulator had `http_proxy=:0`; it received real ETH/BTC streams and 60 candles through this relay. This does not establish reachability from every mobile carrier or region.

Primary references: [Binance market stream endpoints](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Connect), [Android foreground restart semantics](https://developer.android.com/reference/android/app/Service), [background start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Doze network restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby).
