import SwiftUI
import MarketCore

struct CoinManager: View {
    @ObservedObject var store: MarketStore
    let close: () -> Void
    @State private var draft: String
    @State private var search = ""
    @State private var saving = false
    @State private var inputError: String?

    init(store: MarketStore, close: @escaping () -> Void) {
        self.store = store
        self.close = close
        _draft = State(initialValue: store.coins.joined(separator: ", "))
    }

    private var matches: [String] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().replacingOccurrences(of: "/", with: "")
        let entered = Set((try? WatchlistInput.parse(draft)) ?? [])
        return store.availableCoins.filter { coin in
            !entered.contains(coin) && (query.isEmpty || (coin + "USDT").contains(query))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("编辑展示币种").font(.system(size: 20, weight: .semibold))
            Text("直接输入币种代码，按输入顺序展示。")
                .font(.system(size: 12)).foregroundStyle(Palette.muted)
            TextEditor(text: $draft)
                .font(.system(size: 14, design: .monospaced)).scrollContentBackground(.hidden)
                .padding(8).frame(height: 105)
                .background(Palette.card, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Palette.line))
                .accessibilityLabel("展示币种输入框")
            Text("例如 ETH, BTC, SOL · 支持逗号、空格或换行分隔\n也可填写 BTCUSDT、ETH/USDT；保存后下次启动仍保留。")
                .font(.system(size: 10)).foregroundStyle(Palette.muted).lineSpacing(4)
            if let inputError {
                Text(inputError).font(.system(size: 11)).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            }
            Divider().overlay(Palette.line)
            HStack {
                Text("查找合约并填入（可选）").font(.system(size: 11)).foregroundStyle(Palette.muted)
                Spacer()
                if store.loadingCoins { ProgressView().controlSize(.small) }
                Button { Task { await store.loadAvailableCoins() } } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless).disabled(store.loadingCoins).help("刷新合约列表")
            }
            TextField("搜索合约，如 DOGE、1000PEPE", text: $search).textFieldStyle(.roundedBorder)
            if let error = store.coinLoadError {
                Text(error).font(.system(size: 10)).foregroundStyle(.orange)
            }
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(matches, id: \.self) { coin in
                        Button {
                            let existing = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                            draft = existing.isEmpty ? coin : existing + ", " + coin
                            inputError = nil
                        } label: {
                            HStack {
                                Text(coin).font(.system(size: 12, weight: .medium, design: .monospaced))
                                Text("/ USDT 永续").font(.system(size: 10)).foregroundStyle(Palette.muted)
                                Spacer()
                                Image(systemName: "plus.circle").foregroundStyle(Palette.green)
                            }.padding(7).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                    if matches.isEmpty && !store.loadingCoins && store.coinLoadError == nil {
                        Text("没有匹配的合约").font(.system(size: 11)).foregroundStyle(Palette.muted).padding()
                    }
                }
            }.frame(maxHeight: .infinity)
            Divider().overlay(Palette.line)
            HStack(spacing: 12) {
                Button("填入 ETH, BTC") { draft = "ETH, BTC"; inputError = nil }
                    .font(.system(size: 11)).buttonStyle(.borderless)
                Spacer()
                if saving { ProgressView().controlSize(.small) }
                Button("取消", action: close).keyboardShortcut(.cancelAction)
                Button("保存") {
                    saving = true; inputError = nil
                    Task { @MainActor in
                        defer { saving = false }
                        do { try await store.saveCoinInput(draft); close() }
                        catch { inputError = error.localizedDescription }
                    }
                }.keyboardShortcut("s", modifiers: .command).buttonStyle(.borderedProminent).tint(Palette.green)
            }
        }
        .disabled(saving)
        .padding(20).frame(width: 400, height: 570)
        .background(Palette.background).foregroundStyle(.white).preferredColorScheme(.dark)
        .task { if store.availableCoins.isEmpty { await store.loadAvailableCoins() } }
    }
}
