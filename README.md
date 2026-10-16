# Panel

实时行情与波动警报工具，包含 Android App、macOS 悬浮工具和 Go 监测服务。

- [Android 0.14.0](android/README.md)：安装 [Panel-Android.apk](dist/Panel-Android.apk)，显示名称「哨灯 · Panel」。自动保存与配置备份、控件状态同步、后台恢复、直连/新加坡中继及 HTTP 兜底、可切换币安 / Gate 行情来源（Gate 国内免代理）、顶部与状态栏多币同时显示、警报规则可同步到服务器监测，以及日夜提醒和顶部颜色定制，支持 BTC / ETH / 黄金 / 原油永续，保留通知、浮窗、锁屏、充电屏保与外观设置。Android 16+，可覆盖升级保留设置。
- [Go 服务](server/README.md)：已部署在新加坡服务器，监测币安五个合约、Moomoo 八只美股和金色财经 / Tree / Finnhub 新闻；阈值、自选池、关键词均可配置，事件持久化，并执行手机同步过来的警报规则。Finnhub 综合与加密新闻已实测接通；当前记录事件，服务器到手机推送将在后续接入。
- macOS 版使用 SwiftUI + AppKit，无第三方依赖，macOS 14+。下文为桌面版说明，现有构建文件仍为 `Pnale.app`。

Android 的实测范围见 [验证记录](android/VALIDATION.md)，服务器运行证据见 [服务验证](server/VALIDATION.md)。

## 运行

```sh
zsh scripts/build-app.sh
open dist/Pnale.app
```

也可以在 Finder 中双击 `dist/Pnale.app`。构建脚本使用本机架构，生成本地 ad-hoc 签名应用；对外分发前还需要 Developer ID 签名和公证。

## 使用

- **顶部状态栏**：显示当前币种、USDT 价格和 24 小时涨跌方向。左键打开行情卡片，右键切换币种、显示/收起悬浮窗或退出。
- **置顶悬浮窗**：拖动顶部空白处移动；右上角的向上收起按钮隐藏小窗，行情继续在状态栏更新。状态栏卡片右上角的画中画按钮恢复小窗。
- **精简模式**：标题栏的收缩按钮切成仅报价模式，再点一次展开。
- **K 线**：最近 60 根，支持 1m / 5m / 15m / 1h，含当前未收盘 K 线和成交量。悬停查看该根的开盘/收盘价。横轴使用系统本地时间。
- **直接录入币种**：默认 `ETH, BTC`。点击币种栏 `+` 或 `… → 管理自选币种`，直接编辑多行输入框，例如 `ETH, BTC, SOL`。支持逗号、中文逗号、空格和换行；也接受 `BTCUSDT`、`ETH/USDT`，按输入顺序展示并自动去重。点击“保存”后校验 Binance 当前可交易的 U 本位 USDT 永续合约、更新行情并记住配置；关闭或取消则丢弃未保存内容。输入无效时保留原列表并提示具体币种。支持 `1000PEPE` 等完整合约名称。
- 输入框下方可搜索合约并填入，也可一键填入 `ETH, BTC` 再保存。
- **透明度**：浮窗 `…` 菜单支持 100% / 85% / 70% 透明度。
- 记住自选币种、选中币种、周期、浮窗位置、展示模式和透明度。不自动注册开机启动。
- 升级时将旧的初始 BTC/ETH/SOL 列表迁移为 ETH/BTC，保留其他已有自选组合；迁移只执行一次。

## 行情与连接

数据来自 Binance **U 本位 USDT 永续合约**公开市场数据，无需 API Key，无账户或交易功能。价格为合约最新成交价；K 线是合约成交价 K 线，不是标记价格或指数价格。

- REST：`https://fapi.binance.com/fapi/v1/ticker/24hr`、`/klines`。报价使用逐币种 `symbol` 参数并行请求。
- WebSocket：`wss://fstream.binance.com/market/stream`；订阅自选币种的 `@ticker` 和当前币种的 `@kline_<interval>`。
- 首次连接/重连先拉取报价与 60 根 K 线快照，再接收实时推送。WebSocket 错误采用 2–30 秒退避；连接连续 15 秒无行情消息时重建。Mac 唤醒后重新拉取快照。
- 报价超过交易所事件时间 15 秒即变灰并显示“行情已过期”；状态栏出现时钟标记。HTTP 响应成功并不自动认定为实时。
- K 线独立显示“待更新”。断线期间保留最后数据，重连后回填窗口；切币/切周期清空旧图，防止错标。
- 涨跌幅为交易所滚动 24 小时涨跌，绿涨红跌；价格单位是 USDT。
- 网络不可达时展示连接/过期状态，不填充模拟行情。

参考：[Binance U 本位合约行情接口](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)、[合约 WebSocket 入口说明](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Important-WebSocket-Change-Notice)、[Apple NSPanel](https://developer.apple.com/documentation/appkit/nspanel)。

## 开发与验证

```sh
swift build
# XCTest 需要完整 Xcode；本机默认 Command Line Tools 不包含 XCTest。
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --scratch-path .build-xcode
# 请求真实快照、验证快速切币/切周期与重连。每个阶段 25 秒超时退出非零。
.build/debug/Pnale --smoke-test
```

核心测试覆盖 K 线更新/滚动、乱序去重、非法 OHLC/非有限数字、交易所流解析、行情过期与价格精度。

`Sources/MarketCore` 是行情模型和 HTTP 接口；`Sources/Pnale/MarketStore.swift` 管理连接和展示状态；`AppDelegate.swift` 管理状态栏、Popover 和 NSPanel；`Dashboard.swift` 绘制行情卡片与 K 线。

普通桌面窗口使用 `.floating` 层级，并配置所有桌面空间与全屏辅助窗口。全屏 App、系统安全界面和不同多显示器布局仍需按实际使用环境验证；不保证覆盖系统级窗口。
