import SwiftUI
import MarketCore

enum Palette {
    static let background = Color(red: 0.055, green: 0.065, blue: 0.08)
    static let card = Color.white.opacity(0.045)
    static let muted = Color(red: 0.49, green: 0.53, blue: 0.59)
    static let green = Color(red: 0.40, green: 0.89, blue: 0.70)
    static let red = Color(red: 1, green: 0.43, blue: 0.48)
    static let line = Color.white.opacity(0.075)
    static func direction(_ value: Double) -> Color { value >= 0 ? green : red }
}

struct Dashboard: View {
    @ObservedObject var store: MarketStore
    let floating: Bool
    let togglePresentation: () -> Void
    let resize: () -> Void
    let manageCoins: () -> Void
    @State private var hover: Int?

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle().fill(Palette.line).frame(height: 1)
            VStack(alignment: .leading, spacing: 18) {
                coinPicker
                price
                if !store.compact {
                    chart
                    statistics
                    watchlist
                }
            }
            .padding(20)
            footer
        }
        .frame(width: 380)
        .background(Palette.background)
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.line))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "waveform.path").font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.green)
            Text("PNALE").font(.system(size: 11, weight: .bold, design: .monospaced)).tracking(2.5)
            Text("随手盯盘").font(.system(size: 10)).foregroundStyle(Palette.muted)
            Spacer()
            icon(store.compact ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", help: store.compact ? "展开 K 线" : "精简模式") {
                store.compact.toggle(); resize()
            }
            Menu {
                Button("管理自选币种…", action: manageCoins)
                if floating {
                    Section("小窗透明度") {
                        ForEach([1.0, 0.85, 0.7], id: \.self) { alpha in
                            Button("\(Int(alpha * 100))%\(store.opacity == alpha ? " ✓" : "")") { store.opacity = alpha }
                        }
                    }
                }
                Button("重新连接") { store.restart() }
                Divider()
                Button("退出 Pnale", role: .destructive) { NSApp.terminate(nil) }
            } label: { Image(systemName: "ellipsis").frame(width: 23, height: 24) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .help("自选币种与设置")
            icon(floating ? "arrow.up.to.line" : "pip.enter", help: floating ? "收起到顶部状态栏" : "打开置顶悬浮窗", action: togglePresentation)
        }
        .padding(.horizontal, 17).frame(height: 47)
    }

    private var coinPicker: some View {
        HStack {
            HStack(spacing: 0) {
                ForEach(Array(store.coins.prefix(5)), id: \.self) { coin in
                    Button { store.select(coin) } label: {
                        Text(coin).font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(store.selected == coin ? .white : Palette.muted)
                            .lineLimit(1).truncationMode(.tail)
                            .padding(.horizontal, 8).padding(.vertical, 7)
                            .background(store.selected == coin ? Color.white.opacity(0.10) : .clear, in: RoundedRectangle(cornerRadius: 6))
                    }.buttonStyle(.plain)
                }
            }.padding(3).background(Palette.card, in: RoundedRectangle(cornerRadius: 9))
            Spacer(minLength: 4)
            if store.coins.count > 5 {
                Menu { ForEach(store.coins, id: \.self) { coin in Button(coin) { store.select(coin) } } }
                label: { Image(systemName: "chevron.down") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            } else {
                Button(action: manageCoins) { Image(systemName: "plus").font(.system(size: 11)) }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted).help("管理自选币种")
            }
        }
    }

    private var price: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 5) {
                Text(store.selected).fontWeight(.semibold)
                Text("/ USDT 永续").foregroundStyle(Palette.muted)
                Spacer()
                Text("24H").font(.system(size: 9, design: .monospaced)).foregroundStyle(Palette.muted)
            }.font(.system(size: 11))
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(store.quote.map { PriceFormat.string($0.price) } ?? "—")
                    .font(.system(size: 34, weight: .medium, design: .rounded)).monospacedDigit()
                    .tracking(-1).minimumScaleFactor(0.65).lineLimit(1)
                    .foregroundStyle(store.fresh ? .white : Palette.muted)
                Spacer(minLength: 0)
                if let quote = store.quote {
                    HStack(spacing: 3) {
                        Image(systemName: quote.change >= 0 ? "arrow.up.right" : "arrow.down.right")
                        Text(String(format: "%+.2f%%", quote.change)).monospacedDigit()
                    }.font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Palette.direction(quote.change))
                        .padding(.horizontal, 8).padding(.vertical, 5)
                        .background(Palette.direction(quote.change).opacity(0.1), in: Capsule())
                }
            }
        }
    }

    private var chart: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("价格走势").font(.system(size: 10)).foregroundStyle(Palette.muted)
                if !store.chartFresh && !store.candles.isEmpty {
                    Text("待更新").font(.system(size: 9)).foregroundStyle(.orange)
                }
                Spacer()
                ForEach(MarketStore.intervals, id: \.self) { period in
                    Button { hover = nil; store.setInterval(period) } label: {
                        Text(period).font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(store.interval == period ? Palette.green : Palette.muted)
                            .padding(.horizontal, 7).padding(.vertical, 4)
                            .background(store.interval == period ? Palette.green.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 4))
                    }.buttonStyle(.plain)
                }
            }
            if store.candles.isEmpty {
                VStack(spacing: 9) {
                    if store.error == nil { ProgressView().controlSize(.small) }
                    Text(store.error == nil ? "正在获取最近 60 根 K 线" : "K 线暂不可用 · 自动重试中")
                        .font(.system(size: 11)).foregroundStyle(Palette.muted)
                }.frame(maxWidth: .infinity).frame(height: 173)
            } else {
                CandleChart(candles: store.candles, hover: $hover).frame(height: 149)
                HStack {
                    if let index = hover, store.candles.indices.contains(index) {
                        let candle = store.candles[index]
                        Text(candle.date, format: .dateTime.hour().minute())
                        Text("开 \(PriceFormat.string(candle.open))  收 \(PriceFormat.string(candle.close))")
                    } else {
                        Text(store.candles.first!.date, format: .dateTime.hour().minute())
                        Spacer()
                        Text("最近 60 根 · 含未收盘").font(.system(size: 8))
                        Spacer()
                        Text(store.candles.last!.date, format: .dateTime.hour().minute())
                    }
                }.font(.system(size: 9, design: .monospaced)).foregroundStyle(Palette.muted).frame(height: 14)
            }
        }
    }

    private var statistics: some View {
        HStack(spacing: 0) {
            metric("24H 最高", value: store.quote.map { PriceFormat.string($0.high) } ?? "—")
            Spacer()
            metric("24H 最低", value: store.quote.map { PriceFormat.string($0.low) } ?? "—")
            Spacer()
            metric("成交额 · USDT", value: store.quote.map { PriceFormat.compact($0.volume) } ?? "—")
        }.padding(12).background(Palette.card, in: RoundedRectangle(cornerRadius: 9))
    }

    private var watchlist: some View {
        VStack(spacing: 0) {
            HStack {
                Text("自选行情").font(.system(size: 10))
                Spacer()
                Text("价格 / 24H").font(.system(size: 9))
            }.foregroundStyle(Palette.muted).padding(.bottom, 7)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(store.coins, id: \.self) { coin in
                        let quote = store.quotes[coin + "USDT"]
                        Button { store.select(coin) } label: {
                            HStack(spacing: 9) {
                                Circle().fill(coinColor(coin).opacity(0.16)).frame(width: 25, height: 25)
                                    .overlay(Text(String(coin.prefix(1))).font(.system(size: 11, weight: .bold)).foregroundStyle(coinColor(coin)))
                                Text(coin).font(.system(size: 11, weight: .medium))
                                if store.selected == coin { Circle().fill(Palette.green).frame(width: 4, height: 4) }
                                Spacer()
                                Text(quote.map { PriceFormat.string($0.price) } ?? "—")
                                    .foregroundStyle(quote?.isStale(at: store.now) == false ? .white : Palette.muted)
                                Text(quote.map { String(format: "%+.2f%%", $0.change) } ?? "—")
                                    .foregroundStyle(quote.map { Palette.direction($0.change) } ?? Palette.muted)
                                    .frame(width: 65, alignment: .trailing)
                            }.font(.system(size: 11, design: .monospaced)).monospacedDigit().padding(.vertical, 6)
                                .contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.scrollIndicators(.hidden).frame(height: CGFloat(min(store.coins.count, 4)) * 39)
        }
    }

    private var footer: some View {
        HStack(spacing: 5) {
            Circle().fill(store.fresh && store.connection == "实时推送" ? Palette.green : .orange).frame(width: 5, height: 5)
            Text(store.status)
            if let quote = store.quote {
                Text(quote.eventTime, format: .dateTime.hour().minute().second()).monospacedDigit().foregroundStyle(Palette.muted)
            }
            Spacer(minLength: 0)
            Text("Binance · 永续").foregroundStyle(Palette.muted)
            Button { store.restart() } label: { Image(systemName: "arrow.clockwise").font(.system(size: 9)) }
                .buttonStyle(.plain).foregroundStyle(Palette.muted).help(store.error ?? "刷新并重新连接")
        }.font(.system(size: 9)).padding(.horizontal, 20).frame(height: 33)
            .background(Color.black.opacity(0.15))
            .help(store.error ?? "时间为交易所行情时间；超过 15 秒未更新会标记过期")
    }

    private func icon(_ name: String, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.system(size: 12)).frame(width: 23, height: 24).contentShape(Rectangle()) }
            .buttonStyle(.plain).foregroundStyle(Palette.muted).help(help).accessibilityLabel(help)
    }
    private func metric(_ label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 9)).foregroundStyle(Palette.muted)
            Text(value).font(.system(size: 10, weight: .medium, design: .monospaced)).monospacedDigit()
        }
    }
    private func coinColor(_ coin: String) -> Color {
        switch coin { case "BTC": return .orange; case "ETH": return .init(red: 0.60, green: 0.66, blue: 1); case "SOL": return Palette.green; default: return .cyan }
    }
}

struct CandleChart: View {
    let candles: [Candle]
    @Binding var hover: Int?

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                guard !candles.isEmpty else { return }
                let labelWidth: CGFloat = 63
                let width = size.width - labelWidth
                let priceHeight = size.height - 25
                let minPrice = candles.map(\.low).min()!
                let maxPrice = candles.map(\.high).max()!
                let padding = max((maxPrice - minPrice) * 0.12, maxPrice * 0.00005)
                let bottom = minPrice - padding, top = maxPrice + padding
                let y: (Double) -> CGFloat = { CGFloat((top - $0) / (top - bottom)) * priceHeight }
                let step = width / CGFloat(candles.count)
                for row in 0...3 {
                    let value = top - (top - bottom) * Double(row) / 3
                    let position = y(value)
                    var path = Path(); path.move(to: CGPoint(x: 0, y: position)); path.addLine(to: CGPoint(x: width, y: position))
                    context.stroke(path, with: .color(Palette.line), style: StrokeStyle(lineWidth: 0.5, dash: [2, 4]))
                    context.draw(Text(PriceFormat.string(value)).font(.system(size: 8, design: .monospaced)).foregroundColor(Palette.muted), at: CGPoint(x: size.width, y: position + 2), anchor: .trailing)
                }
                let maxVolume = max(candles.map(\.volume).max()!, 1)
                for (index, candle) in candles.enumerated() {
                    let x = CGFloat(index) * step + step / 2
                    let color = Palette.direction(candle.close - candle.open)
                    var wick = Path(); wick.move(to: CGPoint(x: x, y: y(candle.high))); wick.addLine(to: CGPoint(x: x, y: y(candle.low)))
                    context.stroke(wick, with: .color(color), lineWidth: 1)
                    let rect = CGRect(x: x - step * 0.31, y: min(y(candle.open), y(candle.close)), width: max(1, step * 0.62), height: max(1, abs(y(candle.open) - y(candle.close))))
                    context.fill(Path(roundedRect: rect, cornerRadius: 0.6), with: .color(color))
                    let volumeHeight = CGFloat(candle.volume / maxVolume) * 17
                    context.fill(Path(CGRect(x: rect.minX, y: size.height - volumeHeight, width: rect.width, height: volumeHeight)), with: .color(color.opacity(0.24)))
                }
                if let last = candles.last {
                    let position = y(last.close), color = Palette.direction(last.close - last.open)
                    var line = Path(); line.move(to: CGPoint(x: 0, y: position)); line.addLine(to: CGPoint(x: width, y: position))
                    context.stroke(line, with: .color(color.opacity(0.5)), style: StrokeStyle(lineWidth: 0.7, dash: [3, 3]))
                    context.fill(Path(roundedRect: CGRect(x: width + 3, y: position - 8, width: 60, height: 16), cornerRadius: 3), with: .color(color))
                    context.draw(Text(PriceFormat.string(last.close)).font(.system(size: 8, weight: .semibold, design: .monospaced)).foregroundColor(Palette.background), at: CGPoint(x: width + 33, y: position))
                }
                if let hover, candles.indices.contains(hover) {
                    let x = CGFloat(hover) * step + step / 2
                    var line = Path(); line.move(to: CGPoint(x: x, y: 0)); line.addLine(to: CGPoint(x: x, y: size.height))
                    context.stroke(line, with: .color(.white.opacity(0.5)), style: StrokeStyle(lineWidth: 0.7, dash: [3, 3]))
                }
            }
            .onContinuousHover { phase in
                switch phase {
                case .active(let point):
                    let width = geometry.size.width - 63
                    hover = point.x >= 0 && point.x < width && !candles.isEmpty ? min(candles.count - 1, Int(point.x / width * CGFloat(candles.count))) : nil
                case .ended: hover = nil
                }
            }
            .accessibilityLabel("\(candles.count) 根 K 线，最新收盘价 \(candles.last.map { PriceFormat.string($0.close) } ?? "暂无")")
        }
    }
}
