import AppKit
import Combine
import MarketCore

@MainActor
final class MarketStore: ObservableObject {
    static let intervals = ["1m", "5m", "15m", "1h"]
    @Published var availableCoins: [String] = []
    @Published var loadingCoins = false
    @Published var coinLoadError: String?
    @Published var coins: [String]
    @Published var selected: String
    @Published var interval: String
    @Published var quotes: [String: Quote] = [:]
    @Published var candles: [Candle] = []
    @Published var connection = "连接中"
    @Published var error: String?
    @Published var chartUpdated: Date?
    @Published var now = Date()
    @Published var compact: Bool { didSet { defaults.set(compact, forKey: "compact") } }
    @Published var opacity: Double { didSet { defaults.set(opacity, forKey: "opacity") } }
    private let defaults: UserDefaults
    private var generation = UUID()
    private var worker: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    private var clock: AnyCancellable?
    private var socketActivity = Date()
    private let api = MarketAPI()
    private(set) var streamedSymbols = Set<String>()
    private(set) var receivedStreamCandle = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let initialCoins = WatchlistPreferences.load(from: defaults)
        coins = initialCoins
        let previous = defaults.string(forKey: "selected") ?? initialCoins[0]
        selected = initialCoins.contains(previous) ? previous : initialCoins[0]
        let period = defaults.string(forKey: "interval") ?? "1m"
        interval = Self.intervals.contains(period) ? period : "1m"
        compact = defaults.bool(forKey: "compact")
        let alpha = defaults.double(forKey: "opacity")
        opacity = alpha >= 0.5 && alpha <= 1 ? alpha : 1
        clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect().sink { [weak self] date in
            guard let self else { return }
            self.now = date
            if self.socket != nil && date.timeIntervalSince(self.socketActivity) > 15 {
                self.connection = "重连中"
                self.socket?.cancel(with: .goingAway, reason: nil)
                self.socket = nil
            }
        }
    }

    var quote: Quote? { quotes[selected + "USDT"] }
    var fresh: Bool { quote.map { !$0.isStale(at: now) } ?? false }
    var chartFresh: Bool { chartUpdated.map { now.timeIntervalSince($0) <= 15 } ?? false }
    var status: String { !fresh && quote != nil ? "行情已过期" : connection }

    func select(_ coin: String) {
        guard selected != coin else { return }
        selected = coin
        defaults.set(coin, forKey: "selected")
        restart(clearChart: true)
    }
    func setInterval(_ value: String) {
        guard interval != value else { return }
        interval = value
        defaults.set(value, forKey: "interval")
        restart(clearChart: true)
    }
    func toggleCoin(_ coin: String) {
        if coins.contains(coin) {
            guard coins.count > 1 else { return }
            coins.removeAll { $0 == coin }
        } else {
            guard availableCoins.contains(coin) else { return }
            coins.append(coin)
        }
        saveCoins()
    }

    private func saveCoins() {
        let changedSelection = !coins.contains(selected)
        if changedSelection { selected = coins[0] }
        WatchlistPreferences.save(coins, to: defaults)
        defaults.set(selected, forKey: "selected")
        restart(clearChart: changedSelection)
    }

    func resetCoins() {
        coins = WatchlistPreferences.defaultCoins
        saveCoins()
    }

    func saveCoinInput(_ text: String) async throws {
        _ = try WatchlistInput.parse(text)
        let catalog = try await api.perpetualCoins()
        let validated = try WatchlistInput.validated(text, available: catalog)
        availableCoins = catalog
        coins = validated
        saveCoins()
    }

    func moveCoin(_ coin: String, offset: Int) {
        guard let index = coins.firstIndex(of: coin), coins.indices.contains(index + offset) else { return }
        coins.swapAt(index, index + offset)
        WatchlistPreferences.save(coins, to: defaults)
    }

    func loadAvailableCoins() async {
        guard !loadingCoins else { return }
        loadingCoins = true; coinLoadError = nil
        defer { loadingCoins = false }
        do { availableCoins = try await api.perpetualCoins() }
        catch { coinLoadError = "币种列表加载失败：\(error.localizedDescription)" }
    }

    func stop() {
        generation = UUID()
        worker?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
    }

    func restart(clearChart: Bool = false) {
        stop()
        if clearChart { candles = []; chartUpdated = nil }
        connection = "连接中"; error = nil
        streamedSymbols = []; receivedStreamCandle = false
        let token = generation
        let symbol = selected + "USDT", period = interval
        let symbols = coins.map { $0 + "USDT" }
        worker = Task { [weak self] in
            guard let self else { return }
            var failures = 0
            while !Task.isCancelled && self.generation == token {
                var backfillSucceeded = false
                // Backfill on every reconnect so a disconnect never silently leaves missing bars.
                async let quoteSnapshot = self.api.quotes(symbols: symbols)
                async let chartSnapshot = self.api.candles(symbol: symbol, interval: period)
                do {
                    let quotes = try await quoteSnapshot
                    guard self.generation == token && !Task.isCancelled else { return }
                    for quote in quotes { self.quotes[quote.symbol] = quote }
                    self.connection = "快照 · 连接推送中"
                } catch {
                    guard self.generation == token && !Task.isCancelled else { return }
                    self.error = error.localizedDescription
                }
                do {
                    let candles = try await chartSnapshot
                    guard self.generation == token && !Task.isCancelled else { return }
                    self.candles = candles
                    backfillSucceeded = true
                    // The current bar must cover now; old HTTP data must not appear live.
                    let seconds: Double = period == "1m" ? 60 : period == "5m" ? 300 : period == "15m" ? 900 : 3600
                    if let last = candles.last, Date().timeIntervalSince(last.date) < seconds + 15 {
                        self.chartUpdated = Date()
                    } else { self.chartUpdated = nil }
                } catch {
                    guard self.generation == token && !Task.isCancelled else { return }
                    self.error = "K 线：\(error.localizedDescription)"
                    self.chartUpdated = nil
                }
                guard self.generation == token && !Task.isCancelled else { return }
                let streams = symbols.map { $0.lowercased() + "@ticker" } + [symbol.lowercased() + "@kline_" + period]
                let url = URL(string: "wss://fstream.binance.com/market/stream?streams=" + streams.joined(separator: "/"))!
                let ws = URLSession.shared.webSocketTask(with: url)
                self.socket = ws; self.socketActivity = Date(); ws.resume()
                do {
                    while !Task.isCancelled && self.generation == token {
                        let message = try await ws.receive()
                        guard self.generation == token && !Task.isCancelled else { return }
                        let data: Data
                        switch message {
                        case .data(let bytes): data = bytes
                        case .string(let text): data = Data(text.utf8)
                        @unknown default: continue
                        }
                        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                              let payload = envelope["data"] as? [String: Any] else { continue }
                        if payload["e"] as? String == "serverShutdown" { throw URLError(.networkConnectionLost) }
                        if payload["e"] as? String == "24hrTicker" {
                            let quote = try Quote(json: payload, stream: true)
                            self.streamedSymbols.insert(quote.symbol)
                            if self.quotes[quote.symbol].map({ $0.eventTime <= quote.eventTime }) ?? true {
                                self.quotes[quote.symbol] = quote
                            }
                        } else if let k = payload["k"] as? [String: Any],
                                  k["s"] as? String == symbol, k["i"] as? String == period {
                            let candle = try Candle(json: k)
                            // A failed snapshot needs a backfill before a single live bar can count as a chart.
                            if !backfillSucceeded || self.candles.isEmpty { throw MarketError.invalidData }
                            self.candles = CandleSeries.merging(self.candles, [candle])
                            self.receivedStreamCandle = true
                            if let timestamp = number(payload["E"]) { self.chartUpdated = Date(timeIntervalSince1970: timestamp / 1000) }
                        } else { continue }
                        self.socketActivity = Date()
                        self.connection = "实时推送"; self.error = nil; failures = 0
                    }
                } catch {
                    guard self.generation == token && !Task.isCancelled else { return }
                    self.connection = "重连中"; self.error = error.localizedDescription
                }
                ws.cancel(with: .goingAway, reason: nil)
                self.socket = nil
                failures += 1
                let delay = min(30, pow(2, Double(min(failures, 5))))
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
        }
    }
}
