import AppKit
import MarketCore

if CommandLine.arguments.contains("--smoke-test") {
    Task { @MainActor in
        let suite = "app.pnale.smoke.\(UUID().uuidString)"
        let preferences = UserDefaults(suiteName: suite)!
        let store = MarketStore(defaults: preferences)
        @MainActor func restore() {
            store.stop()
            preferences.removePersistentDomain(forName: suite)
        }
        @MainActor func awaitLive() async -> Bool {
            for _ in 0..<25 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if store.connection == "实时推送", store.fresh, store.chartFresh, store.candles.count == 60,
                   store.receivedStreamCandle, store.streamedSymbols.isSuperset(of: store.coins.map { $0 + "USDT" }),
                   store.coins.allSatisfy({ store.quotes[$0 + "USDT"]?.isStale() == false }) { return true }
            }
            return false
        }
        store.restart()
        guard await awaitLive() else {
            print("FAIL: \(store.status); candles=\(store.candles.count); error=\(store.error ?? "none")")
            restore(); exit(1)
        }
        print("PASS: Binance USD-M perpetual REST + WebSocket tickers + streamed kline; \(store.coins.joined(separator: ",")); \(store.selected)/USDT; \(store.interval); \(store.candles.count) candles; \(store.quote!.price); event=\(store.quote!.eventTime)")
        // Rapid changes intentionally race in-flight requests; only the final selection may render.
        for period in ["5m", "15m", "1h"] { store.setInterval(period) }
        if let other = store.coins.first(where: { $0 != store.selected }) { store.select(other) }
        guard store.candles.isEmpty, await awaitLive(), store.quote?.symbol == store.selected + "USDT",
              zip(store.candles, store.candles.dropFirst()).allSatisfy({ $1.time - $0.time == 3_600_000 }) else {
            print("FAIL: rapid symbol/interval switch: \(store.status), \(store.error ?? "none")")
            restore(); exit(1)
        }
        print("PASS: rapid selection changes -> \(store.selected)/USDT 1h; 60 contiguous hourly candles")
        store.restart()
        guard await awaitLive() else {
            print("FAIL: reconnect/backfill: \(store.error ?? "none")")
            restore(); exit(1)
        }
        print("PASS: reconnect, backfill, and live feed resumed")
        await store.loadAvailableCoins()
        guard store.coinLoadError == nil, store.availableCoins.contains("ETH"), store.availableCoins.contains("BTC"),
              let extra = store.availableCoins.first(where: { $0 == "1000PEPE" }) ?? store.availableCoins.first(where: { !store.coins.contains($0) }) else {
            print("FAIL: perpetual catalog: \(store.coinLoadError ?? "missing symbols")")
            restore(); exit(1)
        }
        store.toggleCoin(extra)
        store.moveCoin(extra, offset: -1)
        store.select(extra)
        let reloaded = MarketStore(defaults: preferences)
        guard reloaded.coins == store.coins, reloaded.selected == extra, await awaitLive() else {
            print("FAIL: custom coin persistence or live feed")
            restore(); exit(1)
        }
        print("PASS: catalog \(store.availableCoins.count) contracts; added/reordered/persisted \(extra); live ticker + kline received")
        store.toggleCoin(extra)
        store.resetCoins()
        store.toggleCoin("BTC")
        store.toggleCoin("ETH")
        guard store.coins == ["ETH"], store.selected == "ETH" else {
            print("FAIL: remove selected coin / prevent empty watchlist")
            restore(); exit(1)
        }
        store.resetCoins()
        guard store.coins == ["ETH", "BTC"], preferences.stringArray(forKey: "coins") == ["ETH", "BTC"] else {
            print("FAIL: restore ETH/BTC defaults")
            restore(); exit(1)
        }
        print("PASS: removal, last-coin guard, and saved ETH/BTC defaults")
        do {
            try await store.saveCoinInput("eth，BTCUSDT\n" + extra + "/USDT")
            guard store.coins == ["ETH", "BTC", extra], preferences.stringArray(forKey: "coins") == store.coins else {
                print("FAIL: typed list not persisted"); restore(); exit(1)
            }
            do {
                try await store.saveCoinInput("ETH, PNALE_INVALID_SYMBOL")
                print("FAIL: invalid typed symbol accepted"); restore(); exit(1)
            } catch {
                guard store.coins == ["ETH", "BTC", extra], preferences.stringArray(forKey: "coins") == store.coins else {
                    print("FAIL: invalid input changed saved list"); restore(); exit(1)
                }
            }
            print("PASS: manual entry normalization + save; invalid input preserves previous watchlist")
        } catch {
            print("FAIL: manual entry: \(error.localizedDescription)"); restore(); exit(1)
        }
        restore(); exit(0)
    }
    dispatchMain()
} else {
    MainActor.assumeIsolated {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
    }
}
