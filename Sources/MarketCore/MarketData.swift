import Foundation

public enum MarketError: LocalizedError {
    case invalidData, http(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidData: return "行情数据格式异常"
        case .http(let status): return "行情服务返回 HTTP \(status)"
        }
    }
}

public struct Quote: Sendable {
    public let symbol: String
    public let price: Double
    public let change: Double
    public let high: Double
    public let low: Double
    public let volume: Double
    public let eventTime: Date

    public init(json: [String: Any], stream: Bool = false) throws {
        guard let symbol = json[stream ? "s" : "symbol"] as? String,
              let price = number(json[stream ? "c" : "lastPrice"]), price > 0,
              let change = number(json[stream ? "P" : "priceChangePercent"]),
              let high = number(json[stream ? "h" : "highPrice"]),
              let low = number(json[stream ? "l" : "lowPrice"]),
              let volume = number(json[stream ? "q" : "quoteVolume"]),
              let time = number(json[stream ? "E" : "closeTime"]) else { throw MarketError.invalidData }
        self.symbol = symbol; self.price = price; self.change = change
        self.high = high; self.low = low; self.volume = volume
        self.eventTime = Date(timeIntervalSince1970: time / 1000)
    }

    public func isStale(at date: Date = Date()) -> Bool { date.timeIntervalSince(eventTime) > 15 }
}

public struct Candle: Identifiable, Equatable, Sendable {
    public var id: Int64 { time }
    public let time: Int64
    public let open: Double
    public let high: Double
    public let low: Double
    public let close: Double
    public let volume: Double
    public var date: Date { Date(timeIntervalSince1970: Double(time) / 1000) }

    public init(row: [Any]) throws {
        guard row.count >= 6, let time = number(row[0]),
              let open = number(row[1]), let high = number(row[2]),
              let low = number(row[3]), let close = number(row[4]),
              let volume = number(row[5]), time >= 0, time < Double(Int64.max),
              low > 0, high >= max(open, close), low <= min(open, close), volume >= 0
        else { throw MarketError.invalidData }
        self.time = Int64(time); self.open = open; self.high = high
        self.low = low; self.close = close; self.volume = volume
    }

    public init(json: [String: Any]) throws {
        try self.init(row: [json["t"] as Any, json["o"] as Any, json["h"] as Any,
                           json["l"] as Any, json["c"] as Any, json["v"] as Any])
    }
}

public enum CandleSeries {
    public static func merging(_ existing: [Candle], _ incoming: [Candle], limit: Int = 60) -> [Candle] {
        guard limit > 0 else { return [] }
        var values = Dictionary(existing.map { ($0.time, $0) }, uniquingKeysWith: { _, new in new })
        incoming.forEach { values[$0.time] = $0 }
        return Array(values.values.sorted { $0.time < $1.time }.suffix(limit))
    }
}

public func number(_ value: Any?) -> Double? {
    let parsed: Double?
    if let string = value as? String { parsed = Double(string) }
    else if let numeric = value as? NSNumber { parsed = numeric.doubleValue }
    else { parsed = nil }
    return parsed.flatMap { $0.isFinite ? $0 : nil }
}

public enum PriceFormat {
    public static func string(_ value: Double) -> String {
        let decimals = value >= 1000 ? 2 : value >= 1 ? 3 : value >= 0.01 ? 4 : 6
        return value.formatted(.number.precision(.fractionLength(decimals)).locale(Locale(identifier: "en_US")))
    }
    public static func compact(_ value: Double) -> String {
        if value >= 1_000_000_000 { return String(format: "%.2fB", value / 1_000_000_000) }
        if value >= 1_000_000 { return String(format: "%.1fM", value / 1_000_000) }
        if value >= 1000 { return String(format: "%.1fK", value / 1000) }
        return String(format: "%.0f", value)
    }
}

public struct MarketAPI: Sendable {
    public init() {}
    private func get(_ path: String, query: [URLQueryItem]) async throws -> Data {
        var url = URLComponents(string: "https://fapi.binance.com/fapi/v1/\(path)")!
        url.queryItems = query
        var request = URLRequest(url: url.url!)
        request.timeoutInterval = 12
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw MarketError.invalidData }
        guard response.statusCode == 200 else { throw MarketError.http(response.statusCode) }
        return data
    }
    public func quotes(symbols: [String]) async throws -> [Quote] {
        // USD-M accepts one symbol per request; the spot-only `symbols` parameter is unsupported.
        return try await withThrowingTaskGroup(of: Quote.self) { group in
            for symbol in Set(symbols) {
                group.addTask {
                    let data = try await get("ticker/24hr", query: [.init(name: "symbol", value: symbol)])
                    guard let row = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw MarketError.invalidData }
                    let quote = try Quote(json: row)
                    guard quote.symbol == symbol else { throw MarketError.invalidData }
                    return quote
                }
            }
            var values: [String: Quote] = [:]
            for try await quote in group { values[quote.symbol] = quote }
            return symbols.compactMap { values[$0] }
        }
    }
    public func perpetualCoins() async throws -> [String] {
        let data = try await get("exchangeInfo", query: [])
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let symbols = object["symbols"] as? [[String: Any]] else { throw MarketError.invalidData }
        let coins = symbols.compactMap { item -> String? in
            guard item["contractType"] as? String == "PERPETUAL",
                  item["status"] as? String == "TRADING",
                  item["quoteAsset"] as? String == "USDT",
                  item["marginAsset"] as? String == "USDT",
                  let symbol = item["symbol"] as? String, symbol.hasSuffix("USDT") else { return nil }
            // Keep contract multipliers such as 1000PEPE, matching both REST and WS symbols.
            return String(symbol.dropLast(4))
        }
        guard !coins.isEmpty else { throw MarketError.invalidData }
        return Array(Set(coins)).sorted()
    }
    public func candles(symbol: String, interval: String) async throws -> [Candle] {
        let data = try await get("klines", query: [.init(name: "symbol", value: symbol),
            .init(name: "interval", value: interval), .init(name: "limit", value: "60")])
        guard let rows = try JSONSerialization.jsonObject(with: data) as? [[Any]], !rows.isEmpty else { throw MarketError.invalidData }
        return try rows.map { try Candle(row: $0) }
    }
}
