# 独立手机行情中继更新 — 2026-09-21

`pnale-relay.service` 已作为独立服务发布在现有新加坡主机的 HTTPS/WSS 443，Android 0.8.0 自动/手动使用。原 `pnale-monitor` 未重启、未改配置。两服务均 active/running、自动重启 0；中继采样占用约 6.5 MiB。已核对本地/服务器二进制 hash 一致，并从无 HTTP 代理的 Android 模拟器取得真实 WS 报价和 K 线，验证断线切换/HTTP/网络恢复。

具体接口限制、TLS 证书维护、测试和边界见 [relay/README.md](relay/README.md)，[服务状态](validation/v08/relay-runtime.txt)。这不是服务器新闻/异常事件的手机推送通道；这些仍未接通。

---

# Panel 服务验证 — 2026-09-20

## 最高级新闻与日夜提醒策略 — 2026-09-20 19:47 SGT

- 已发布 `20260920-v05`；21 项 Go 测试（含 race）与 `go vet` 通过。新测试覆盖金色财经 5/4/6/0 级、缺失等级、Tree/Finnhub、关键词及“精选”不能单独通知，以及旧新闻重启后不能回放通知。[策略测试](validation/go-tests-policy.txt)。
- 金色财经官方网站当前 JS 的最高/红色筛选使用 `grade=5`。只接受这一明确来源等级，且新闻必须满足原有新鲜度与启动后发布限制；合格新闻标记 `notification.mode=normal`，不使用闹钟声音。当前 Tree/Finnhub 载荷没有已验证的最高级字段，保留记录。
- 行情异常事件改为 `sound_policy=device_local_schedule`；由手机当地时间和用户设置决定铃声。默认 22:00–08:00 铃声＋振动，白天仅振动，服务端不按自己的时区替手机决定。
- 最终采样 HTTP **200 / ok=true**，服务 active/running、自动重启 0；五个币安合约连续观察 839–849 秒，均有实际成交。三路新闻成功时间均推进且错误为空；美股保持周日休市状态。
- 本地和服务器二进制 SHA-256 一致：`8090c67b7fb6becad78fde5496d11645f079b2f24694e050f1d24ac5cdb1fbaf`。重启前已有事件 ID 全部保留，旧版新闻在查询 API 中均不再具备通知资格；日文件保持原始记录。
- v05 自然新收两条金色财经四级新闻和一条无等级 Tree 新闻，均为 `record_only / alert_eligible=false`。本次没有自然收到一条新鲜五级新闻；五级正常通知资格通过受控载荷测试，不当作已经向手机发送的证据。
- [完整部署状态](validation/runtime-v05-final.json)、[API 策略及历史保留核验](validation/policy-v05-check.json)、[规则接口](validation/rules-v05.json)。服务器到手机推送仍未接入；以上是服务端筛选与投递策略。

## Finnhub 启用更新 — 2026-09-20T19:07:44+08:00

用户提供的 Key 已配置在服务器 `/etc/pnale-monitor/secrets.env`（root / 0600），只更新该服务的密钥和 Finnhub 开关，未写入源码或 APK。`news.finnhub_enabled=true` 已同步到本地配置。

- 实测 `general` 和 `crypto` 均返回 HTTP 200，分别包含 100 / 89 条有效新闻；服务实际保存 189 条，其中 10 条命中重要新闻规则。
- 首次历史条目 `alert_eligible` 均为 false。按 60 秒间隔轮询，后续成功时间已比首次推进 61541ms，错误为空，重复轮询未增加重复 ID。
- 五个币安合约均重新收到成交；Moomoo 已恢复 LV3 / 100 额度的连接和八只股票的休市状态。存储错误为空，既有事件完整保留。
- 采样时行情仍在重启后的五分钟窗口积累阶段，整体健康检查 HTTP 503，Finnhub 本身已成功取数。付费档位、最大配额、公司情感评分未测试。
- 新闻仍只保存至服务端；手机推送尚未接入。

[首次接口与服务取数证据](validation/finnhub-activation.json)，[再次成功轮询及完整运行状态](validation/runtime-finnhub-followup.json)。下方为启用前的初次部署验证，Finnhub 当前状态以上述更新为准。

## 初次部署状态

- 主机：用户指定的新加坡服务器 `43.156.153.114:2222`，Debian 12 / amd64。
- 当前发布：`/opt/pnale-monitor/releases/20260920-v04`，systemd `pnale-monitor.service`，非 root 用户 `pnale`。
- 最终采样：**2026-09-20T18:57:06+08:00**；`/healthz` 返回 **HTTP 200 / ok=true**，进程 active/running，自动重启次数 0。
- 采样时占用内存约 88.4 MiB（包含 Go 与 Python 行情桥）；总 CPU 时间 1.70s。这是短期运行采样，不是高峰容量证明。
- 部署二进制与本地构建 SHA-256 一致：`0eb00a92626eb5a8fd3f0e8309d9753ed734f702a9c139eabb250895746c675f`；配置文件哈希也一致。
- [完整最终状态与持久化检查](validation/runtime-final.json)，[重启预热状态](validation/runtime-restart-warmup.json)，[前一版本连续运行状态](validation/status-before-final.json)。

## 实时行情与连续窗口

当前版本已实收 BTC / ETH / XAU / CL / BZ 的币安聚合成交。两次状态采样间，五个标的的成交 ID 均推进。最终状态：

| 合约 | 状态 | 连续观察秒数 | 最近成交距采样毫秒 | 当前进程间隔重置 |
| --- | --- | ---: | ---: | ---: |
| BTCUSDT | live | 376 | 510 | 0 |
| BZUSDT | live | 369 | 5605 | 0 |
| CLUSDT | live | 376 | 661 | 0 |
| ETHUSDT | live | 376 | 217 | 0 |
| XAUUSDT | live | 373 | 3170 | 0 |

当前版本显式断线重连计数 0，拒收计数均为 0，存储错误为空。商品合约使用较宽的最大无成交间隔（XAU/BZ 90s，CL 60s），BTC/ETH 为 15s；延迟抵达超过 15s 的成交仍拒收。`quiet`、过期与断线状态均有明确表示。观察连续不代表完整捕获了交易所每一笔成交。

19 项 Go 测试及 race 检查、`go vet`、4 项 Python 行情桥测试通过。[Go 结果](validation/go-tests.txt)，[桥接测试](validation/bridge-tests.txt)。覆盖完整窗口、穿越前基准、1/3/5 分钟事件唯一性、冷却、上下插针顺序、过期/重复/断线重置、持久化去重、实际 WS e/E 字段解析、新闻与美股时间/成交过滤。

本次自然行情没有触发急跌、急涨或插针事件；这些规则通过可控样本验证，初始阈值未做历史收益或漏报率校准。服务只记录事件，未接入交易或向手机发送警报。

## 新闻与事件持久化

- 金色财经已从公共网站接口收到新闻，Tree News 公开 WebSocket 也已收到实际消息。最终 daily JSONL 中有 **103 条新闻（30 条标为重要）**：金色财经 101，Tree 2。
- 另有历史调试阶段记录的 7 条 `data_gap`。这些是早期商品无成交间隔产生的状态事件，不能算行情异常；最终 v04 当前进程间隔重置为 0。
- 共 110 条事件、110 个唯一 ID、0 重复 ID，文件 49,865 字节。更新到 v04 后，重启前 API 中的全部 110 个 ID 均保留。
- v04 采样期间没有新 Tree 新闻到达；连接心跳正常。实际新闻抵达证据来自同日早先版本的已保存事件，不把心跳当作新闻。
- Tree 使用公开档，可能有延迟和来源限制；未验证付费权限，也未声称 Bloomberg / Reuters 全量低延迟覆盖。初次部署时 Finnhub 无 API key，处于关闭状态；后续已接通，见上方启用更新。
- 启动积压及旧新闻保存为不可告警。当前所有事件仅落盘，`alert_eligible` 只是一项以后可供投递层使用的字段。

## Moomoo 美股

既有 OpenD 的美股 LV3 权限、100 个订阅额度已经实查，当前桥接成功订阅 SPY、QQQ、AAPL、NVDA、TSLA、MSFT、AMD、META 的 `TICKER`。该独立连接不修改原有股票分析流水线。

采样是周日，八只股票均为 `AFTER_HOURS_END`，API 明确显示 `market_closed`，没有以旧价格充当实时成交。下次交易时段的自然逐笔流、覆盖交易场所与延迟尚未验证。扩大自选池可以改配置，但最多 100 的本地上限不等于账户始终有 100 个可用额度；其他连接会占用同一账户配额。

## 运行范围

API 只监听 `127.0.0.1:8797`，可通过 SSH 转发查看。数据保存于 `/var/lib/pnale-monitor`，不自动删除日文件；完整配置、阈值和回滚方式见 [README.md](README.md)。最终构建单独重启了 `pnale-monitor`，保留既有 OpenD、FRP、交易服务。

手机 App 当前独立监测币安行情。服务端到手机推送、App 管理服务端配置属于后续阶段。原油/黄金口径为币安传统金融永续，非直接期货交易所行情。
