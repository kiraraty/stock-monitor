import XCTest
@testable import MarketCore

final class MarketDataTests: XCTestCase {
    private func candle(_ time: Int, close: String = "11") throws -> Candle {
        try Candle(row: [time, "10", "12", "9", close, "100"])
    }
    func testStreamingCandleReplacesOpenBarAndRollsWindow() throws {
        let previous = try (0..<60).map { try candle($0 * 60_000) }
        let update = try candle(59 * 60_000, close: "12")
        let merged = CandleSeries.merging(previous, [update])
        XCTAssertEqual(merged.count, 60)
        XCTAssertEqual(merged.last?.close, 12)
        let next = CandleSeries.merging(merged, [try candle(60 * 60_000)])
        XCTAssertEqual(next.count, 60)
        XCTAssertEqual(next.first?.time, 60_000)
        XCTAssertEqual(next.last?.time, 3_600_000)
    }
    func testOutOfOrderBackfillIsSortedAndDeduplicated() throws {
        let result = CandleSeries.merging([try candle(120_000)], [try candle(0), try candle(60_000), try candle(60_000)])
        XCTAssertEqual(result.map(\.time), [0, 60_000, 120_000])
    }
    func testBadOHLCAndNonfiniteDataAreRejected() {
        XCTAssertThrowsError(try Candle(row: [0, "10", "8", "9", "11", "100"]))
        XCTAssertThrowsError(try Candle(row: [0, "nan", "12", "9", "11", "100"]))
        XCTAssertThrowsError(try Candle(row: [0, "10"]))
        XCTAssertNil(number("Infinity"))
    }
    func testStreamCandleDecodesExchangeShape() throws {
        let value = try Candle(json: ["t": 60_000, "o": "10", "h": "12", "l": "9", "c": "11", "v": "100"])
        XCTAssertEqual(value, try candle(60_000))
    }
    func testStalenessUsesExchangeTimeRatherThanReceiptTime() throws {
        let quote = try Quote(json: ["s": "BTCUSDT", "c": "10", "P": "-1", "h": "12", "l": "9", "q": "100", "E": 1_000_000], stream: true)
        XCTAssertFalse(quote.isStale(at: Date(timeIntervalSince1970: 1014)))
        XCTAssertTrue(quote.isStale(at: Date(timeIntervalSince1970: 1016)))
        XCTAssertEqual(quote.change, -1)
    }
    func testSmallCoinPriceRemainsVisible() {
        XCTAssertEqual(PriceFormat.string(0.000123), "0.000123")
        XCTAssertEqual(PriceFormat.string(80566.12), "80,566.12")
    }
}
