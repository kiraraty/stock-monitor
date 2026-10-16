import Foundation

public enum WatchlistInputError: LocalizedError {
    case empty, unavailable([String])
    public var errorDescription: String? {
        switch self {
        case .empty: return "请至少输入一个币种，例如 ETH, BTC"
        case .unavailable(let coins): return "未找到可交易的 U 本位永续合约：" + coins.joined(separator: "、")
        }
    }
}

public enum WatchlistInput {
    public static func parse(_ text: String) throws -> [String] {
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ",，、;；"))
        var coins: [String] = []
        for token in text.uppercased().components(separatedBy: separators) where !token.isEmpty {
            var coin = token
            if coin.hasSuffix("/USDT") { coin = String(coin.dropLast(5)) }
            else if coin.hasSuffix("USDT") { coin = String(coin.dropLast(4)) }
            guard !coin.isEmpty else { throw WatchlistInputError.unavailable([token]) }
            if !coins.contains(coin) { coins.append(coin) }
        }
        guard !coins.isEmpty else { throw WatchlistInputError.empty }
        return coins
    }

    public static func validated(_ text: String, available: [String]) throws -> [String] {
        let coins = try parse(text)
        let supported = Set(available)
        let missing = coins.filter { !supported.contains($0) }
        guard missing.isEmpty else { throw WatchlistInputError.unavailable(missing) }
        return coins
    }
}

public enum WatchlistPreferences {
    public static let defaultCoins = ["ETH", "BTC"]

    public static func load(from defaults: UserDefaults) -> [String] {
        let saved = defaults.stringArray(forKey: "coins")
        // Migrate only the old untouched default. Preserve an explicitly customized list.
        let useNewDefault = defaults.integer(forKey: "watchlistVersion") < 1 && saved == ["BTC", "ETH", "SOL"]
        let candidates = useNewDefault ? defaultCoins : (saved ?? defaultCoins)
        let coins = candidates.reduce(into: [String]()) { result, coin in
            if !coin.isEmpty && !coin.contains("/") && !result.contains(coin) { result.append(coin) }
        }
        let resolved = coins.isEmpty ? defaultCoins : coins
        save(resolved, to: defaults)
        return resolved
    }

    public static func save(_ coins: [String], to defaults: UserDefaults) {
        defaults.set(coins, forKey: "coins")
        defaults.set(1, forKey: "watchlistVersion")
    }
}
