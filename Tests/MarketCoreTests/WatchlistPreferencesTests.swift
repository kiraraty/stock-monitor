import XCTest
@testable import MarketCore

final class WatchlistPreferencesTests: XCTestCase {
    func testTypedCodesNormalizeSeparatorsSuffixesAndDuplicates() throws {
        XCTAssertEqual(try WatchlistInput.parse("eth，BTCUSDT\nSOL/USDT eth;1000pepe"), ["ETH", "BTC", "SOL", "1000PEPE"])
    }
    func testTypedCodesValidateWholeListAndKeepOrder() throws {
        XCTAssertEqual(try WatchlistInput.validated("btc eth", available: ["ETH", "BTC"]), ["BTC", "ETH"])
        XCTAssertThrowsError(try WatchlistInput.validated("ETH, FAKE", available: ["ETH", "BTC"]))
        XCTAssertThrowsError(try WatchlistInput.parse(" , ， \n"))
        XCTAssertThrowsError(try WatchlistInput.parse("USDT"))
    }
    private func withPreferences(_ check: (UserDefaults) -> Void) {
        let name = "app.pnale.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        check(defaults)
    }
    func testFirstRunPersistsETHAndBTC() {
        withPreferences { defaults in
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["ETH", "BTC"])
            XCTAssertEqual(defaults.stringArray(forKey: "coins"), ["ETH", "BTC"])
        }
    }
    func testOldDefaultMigratesOnceWithoutResettingLaterChoices() {
        withPreferences { defaults in
            defaults.set(["BTC", "ETH", "SOL"], forKey: "coins")
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["ETH", "BTC"])
            WatchlistPreferences.save(["BTC", "ETH", "SOL"], to: defaults)
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["BTC", "ETH", "SOL"])
        }
    }
    func testCustomCoinsAndTheirOrderSurviveReload() {
        withPreferences { defaults in
            defaults.set(["1000PEPE", "ETH", "DOGE"], forKey: "coins")
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["1000PEPE", "ETH", "DOGE"])
            WatchlistPreferences.save(["DOGE", "1000PEPE"], to: defaults)
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["DOGE", "1000PEPE"])
        }
    }
    func testEmptyListRecoversAndDuplicatesAreRemoved() {
        withPreferences { defaults in
            defaults.set([String](), forKey: "coins")
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["ETH", "BTC"])
            defaults.set(["ETH", "ETH", "BTC"], forKey: "coins")
            XCTAssertEqual(WatchlistPreferences.load(from: defaults), ["ETH", "BTC"])
        }
    }
}
