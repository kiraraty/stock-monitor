# 哨灯 · Panel Android 0.8.0 验证 — 2026-09-21

当前安装包：[Panel-Android.apk](../dist/Panel-Android.apk)，**0.8.0 / code 8**，Android 16+。包名与 debug 签名保持兼容，可覆盖升级；0.7.0 已保存为 `Panel-Android-0.7.0.apk`。

SHA256 `fe947d60c72162db6d4afe6ba99286007339e37cabb64291a2179ea001f75def`，4,448,216 字节。[版本](validation/v08/apk-badging.txt)、[签名](validation/v08/apk-signature.txt)。

## 本次实现与证据

- 顶部手选颜色同时关闭涨跌配色；断线只改变状态文本，不再覆盖固定色。控件通过配置监听和页面恢复同步，避免开关和选中按钮保留旧值。
- 配置即时保存并自动生成完整本机备份，包含显示方式、启动意图、字体、颜色、自选和警报。原子文件保存最近/上一份快照，整份类型/版本校验通过才恢复；恢复日志处理两个偏好文件之间被中断的情况。设置提供 JSON 导出/导入。
- 自选停顿后自动验证并应用，异步过期校验不能覆盖新输入；不合法输入保留草稿。有效警报编辑自动保存且保留原启用状态，不完整输入可恢复为草稿，新规则默认停用。
- `PANEL_RELIABILITY_PASS`：实际页面点选颜色、字体、开关同步；包含重连状态的 TextView 色值验证；自动备份读取/恢复、无效文件不污染配置；自选有效/无效输入与警报不完整草稿重开；实际服务恢复顶部浮窗、主动停止后不会自动重开。[结果](validation/v08/reliability.txt)。
- `PANEL_REOPEN_PASS`：独立进程中读取已保存的五标的、字体、颜色、开启状态和顶部模式；打开首页后恢复实际浮窗。[结果](validation/v08/reopen.txt)。
- 实际后台进程被 `run-as ... kill -9` 终止后，系统在 **2 秒**内以新 PID 重建前台服务；保留模式与颜色。[PID 证据](validation/v08/sticky-restart.json)、[系统服务状态](validation/v08/service-restored.txt)。这项是单次 Android 模拟器进程死亡恢复，不是 OPPO 整夜保活证明。
- `PANEL_NETWORK_RESILIENCE_PASS`：可控直连失败后自动转真实 TLS 中继，收到 ETH/BTC 新鲜 WS 与 60 根 K 线；可控 WS 失败时 HTTP 源时间推进且警报暂停；解除故障后自动回到 WS 并验证 K 线连续。实际开关飞行模式也验证了断网标记与网络恢复回调重连。[结果](validation/v08/network.txt)。模拟器 `http_proxy=:0`，未设置测试代理。
- `PANEL_ALARMS_PASS`：新版实际界面保存/复制/启用多条规则；真实 `USAGE_ALARM` 播放状态和通知；停止 PendingIntent 与 10 秒超时释放音频、通知、试听服务；最终首页取得真实行情。[结果](validation/v08/alarms.txt)。隔离模拟器使用 `-no-audio`，不证明扬声器或实际振动强度。
- 最终主包构建、**31 项单元测试**和 lint 通过，0 错误 / 36 警告。[构建](validation/v08/build.txt)、[测试汇总](validation/v08/unit-tests.json)。中继独立的 4 项 Go race 测试及 vet 通过。[中继说明与部署验证](../server/relay/README.md)。

## 已目视检查

[固定色与选中按钮](validation/v08/device/panel-v08-colors.png)、[自动保存规则](validation/v08/device/panel-v08-autosave.png)、[自动备份入口](validation/v08/device/panel-v08-backup.png)、[实际桌面顶部蓝色价格](validation/v08/device/panel-v08-top-live.png)。顶部图中白色小数字是系统通知图标，右侧蓝色数字是可调颜色的实际悬浮文字。`panel-v08-overlay.png` 仍是系统权限页（该页隐藏浮窗），不作浮窗显示证明；`panel-v08-reopen.png` 为恢复服务时行情仍连接中的截屏，不作实时行情证据。

## 边界

- OPPO/ColorOS 真机、锁屏整夜网络与省电、自启动权限、系统强行停止后的行为未实测。提供标准前台服务恢复和系统权限入口，未使用无限唤醒锁或强制修改电池策略。开机恢复开关默认关闭，本次没有实测手机重启广播。
- 本机自动备份随卸载/清除数据删除；需要跨手机或卸载保留时先导出。已通过实际系统文件选择器导出到 Downloads，再选取并导入，确认恢复成功且导出/恢复的 watchlist 与 alarm 内容完全一致。[文件交互结果](validation/v08/file-picker.txt)、[恢复提示](validation/v08/device/panel-v08-import.png)。
- 手机运营商到新加坡中继的可达性仍需该手机网络实测。HTTP 兜底属于快照，明确暂停价格警报，不能据此声称没有漏报。
- 此版本没有验证自然暴跌唤醒，没有接通服务器新闻/异常事件到手机的推送；macOS 未修改。

## 重现

在明确选择的隔离设备通过系统界面允许通知和悬浮权限后：

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline --console=plain --max-workers=1
adb -s emulator-5556 shell am instrument -w -e reliability true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am force-stop app.pnale.ticker
adb -s emulator-5556 shell am instrument -w -e reopen true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e resilientNetwork true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e alarms true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

测试会修改配置、切换飞行模式并短时试听，只在可丢弃的测试实例运行。必须看到每项明确 PASS；不能把 adb 的退出码当成通过。

---

# 哨灯 · Panel Android 0.7.0 验证 — 2026-09-20

当前安装包：[Panel-Android.apk](../dist/Panel-Android.apk)，**0.7.0 / code 7**，最低 Android 16 / API 36。包名 `app.pnale.ticker` 和 debug 签名证书与 0.6.0 一致，可覆盖升级；旧包归档为 `Panel-Android-0.6.0.apk`。

SHA-256：`c6fdda51571717f6066731860170da423adcb8adde374117a6b7ca73639cbb95`，4,275,963 字节。`Pnale-Android.apk` 与当前 Panel 文件字节一致。[版本信息](validation/apk-badging-0.7.0.txt)、[签名验证](validation/apk-signature-0.7.0.txt)。

## 本次改动与验收

- 日夜自动提醒使用手机当地时间，在每次触发与试听时解析。默认夜间 22:00–08:00 铃声＋振动、白天仅振动；可修改起止或选择手动模式。规则自身的关闭选项仍保留。夜间开始包含、结束不包含，支持跨午夜；开始结束相同会拒绝保存。
- 页面分为统一卡片，次要设置可折叠；警报保存操作固定在底部。界面字体有标准、纤细、等宽、衬线，字号为 90%–120%。顶部文字单独支持五种字体、10–26 sp、预设/六位自定义颜色、阴影和 24H 涨跌配色。状态栏数字图标共用字体；颜色/尺寸和流体云文字排版仍由系统决定。
- 最终构建、26 项单元测试、lint 通过，lint 0 错误 / 34 警告。新增日夜边界、跨日/同日窗口、手动模式、规则关闭项和六位颜色输入验证。[构建](validation/build-0.7.0.txt)、[测试汇总](validation/unit-tests-0.7.0.json)。
- 在独立只读 Android 16 模拟器 `emulator-5556` 安装运行，通过系统界面允许通知和悬浮权限。**PANEL_APPEARANCE_SCHEDULE_PASS**：界面衬线 / 120% 持久化并实际应用，重复应用不会累计放大；顶部独立等宽、字号滑杆、自定义 `#12ABEF` 均生效。真实浮窗区域在不透明测试条件下检出 1,314 个匹配颜色像素。[测试结果](validation/appearance-schedule-0.7.0.txt)。
- 同一测试实际运行日间、夜间和手动仅振动试听：日间没有 `USAGE_ALARM` 播放或 fullScreenIntent；夜间有实际闹钟音频播放；手动仅振动覆盖夜间；停止后音频、通知及仅为试听开启的服务释放。系统振动服务历史记录三次由 `app.pnale.ticker` 发起并结束的重复振动，最终 IDLE。[振动记录](validation/vibration-v07.txt)。
- **PANEL_ALARMS_PASS**：页面保存、复制、启用多条规则，3 分钟自定义窗口持久化；真实停止 PendingIntent 与 10 秒试听超时均清理音频、通知和服务。[规则与音频回归](validation/alarm-smoke-0.7.0.txt)。
- **PANEL_COMMODITIES_PASS**：首页到设置、分组展开/收起、输入保存五个合约、返回首页、XAU / CL / BZ 的新鲜价格与 60 根 K 线全部通过。首页自选行报价挤窄的问题已修复。[行情与导航回归](validation/commodity-smoke-0.7.0.txt)。

## 画面证据

[日夜提醒与固定操作栏](validation/panel-v07-alarm-schedule.png)、[顶部文字编辑](validation/panel-v07-top-style.png)、[实际自定义色浮窗](validation/panel-v07-top-overlay.png)、[大字号首页](validation/panel-v07-home-large-font.png)、[商品自选首页](validation/panel-v07-home.png)、[分组设置](validation/panel-v07-commodity-settings.png)。截图中的衬线 / 120% 为验证所选配置，安装默认仍为标准字体 / 100%。浮窗截图的位置是测试位置，未把浮窗当作系统通知图标。

## 明确边界

- 本次是模拟器验证，使用 `-no-audio`。音频服务与振动服务记录通过，不证明真实扬声器音量、振动强度或 ColorOS 勿扰/省电行为。OPPO 真机及锁屏整夜唤醒未验证。
- 没有等待自然暴跌触发：规则触发由可控单元样本覆盖，设备测试验证真实试听及释放路径。
- 最高级新闻策略已部署到 Go 服务：当前金色财经严格 `grade=5`，普通通知候选、不用闹钟；Tree/Finnhub 无已验证等级而保留记录。**服务器到手机推送尚未连接**，本次手机价格警报继续使用本地行情。[服务策略证据](../server/VALIDATION.md)。
- 本次没有重新验证全部 DreamService / ColorOS 胶囊场景，也没有修改 macOS 版本。APK 内不含模拟器代理，测试后关闭隔离实例。

## 重现

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline --console=plain --max-workers=1
# 仅在隔离测试设备安装两个 APK，并通过系统界面允许通知、悬浮。
adb -s emulator-5556 shell am instrument -w -e appearance true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e alarms true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e commodities true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

检查每一项明确的 PASS 标记，不能只看 adb shell 退出码。测试会改变隔离实例设置并启动短时警报，不要对用户真机自动运行。

# Historical Android 0.6.0 validation — 2026-09-20

历史安装包：[Panel-Android-0.6.0.apk](../dist/Panel-Android-0.6.0.apk)，版本 **0.6.0 / code 6**，最低 Android 16 / API 36，应用显示名称「哨灯 · Panel」。包名保留 `app.pnale.ticker` 以兼容覆盖升级。

SHA-256：`1062663074be82681f3fb70ab8c9a0c3ef1978dd21c24d4fc479c6c39b5acd5c`，大小 4,219,643 字节。旧下载路径 `Pnale-Android.apk` 与新文件内容一致；原 0.4.0 包另存归档。已验证 debug v2 签名和版本信息：[签名](validation/apk-signature-0.6.0.txt)、[包信息](validation/apk-badging-0.6.0.txt)。

## 已验证

- 最终构建和 lint 通过，0 错误 / 33 警告；21 项单元测试通过（8 行情模型、10 警报规则、3 外观格式），包括完整观察窗口、急涨急跌、双向价格穿越、过期/间隔重置、多条规则独立处理、单次停用/重复冷却与序列化。[构建记录](validation/build-0.6.0.txt)，[单元测试汇总](validation/unit-tests-0.6.0.json)。
- 在隔离的只读 Android 16 模拟器 `emulator-5556` 上安装并运行。最终包警报测试明确返回 **PANEL_ALARMS_PASS**：[测试结果](validation/alarm-smoke.txt)。实际页面保存、复制和单独启用规则成功，自定义 3 分钟窗口与阈值持久化；修复了复制时被下拉框自动选项覆盖的问题。
- 测试实际查看 Android `activePlaybackConfigurations`，确认 `USAGE_ALARM` 音频播放与警报通知存在。真实通知停止 PendingIntent 释放音频、通知及仅为试听启动的前台服务；第二次试听在 10 秒后自动停止。
- 连续保存/启用时旧 Toast 会取消，避免延迟显示错误的旧状态。最终 [规则页面](validation/panel-alarm-rules.png) 与 [警报页面](validation/panel-alarm-ringing.png) 已目视检查。
- 商品行情验证明确返回 **PANEL_COMMODITIES_PASS**：[结果](validation/commodity-smoke.txt)。从首页进入设置、展开/收起分组、实际输入并保存 `ETH, BTC, XAU, CL, BZ`，重新读取偏好确认持久化；五个标的都收到新鲜 WS 报价。返回首页依次选择 XAU / CL / BZ，每个均收到 60 根 K 线与 WS 更新。
- [首页](validation/panel-home.png)、[黄金](validation/panel-XAU.png)、[WTI 原油](validation/panel-CL.png)、[布伦特原油](validation/panel-BZ.png)、[自选设置](validation/panel-commodity-settings.png)。商品与首页检查后仅修正警报页面提示；对应行情代码保持一致，最终包再次通过警报与首页实时数据检查。
- 测试结束停用并还原临时规则，恢复测试设备自选，不向真实手机触发响铃。临时代理仅用于测试模拟器，不写入 APK；验收后恢复代理并关闭该隔离模拟器。

## 验证边界

- 模拟器使用 `-no-audio`，验证的是系统进入闹钟音频播放状态与正确释放，不是扬声器响度。OPPO 真机音量、振动强度、勿扰模式、锁屏全屏唤醒和长时间省电策略仍需在真机上检查。
- 单元测试验证可控行情触发逻辑；设备上验证试听的真实音频/通知生命周期，没有等待一次自然大跌来证明端到端自然事件唤醒。
- 默认没有启用规则。App 不自动开机启动，不保证被系统终止后继续监测；持续监测受后台网络限制，响铃只持有有时限的唤醒锁。
- 手机本地规则使用币安行情。新加坡 Go 服务独立记录行情异常和新闻，服务到手机的推送及 App 编辑服务端配置尚未接入。Finnhub 随后已在服务端启用，见 [服务验证更新](../server/VALIDATION.md)。
- 本次没有重跑旧版所有 ColorOS / DreamService / 各浮窗样式场景。旧证据按版本保留在下方，不当作新版真机验收。
- macOS 应用未包含在这次 Android 改版验收中。

## 重现

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline --console=plain --max-workers=1
# 仅在明确选择的隔离测试设备安装两个 APK，先通过系统界面允许通知。
adb -s emulator-5556 shell am instrument -w -e alarms true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
adb -s emulator-5556 shell am instrument -w -e commodities true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

检查 `PANEL_ALARMS_PASS` / `PANEL_COMMODITIES_PASS`，不要只依赖 `am instrument` 的 shell 退出码。警报测试要求空规则的隔离设备，会播放测试警报；不要对用户真机自动运行。

# Historical Android 0.4.0 validation — 2026-09-20

Historical artifact: `../dist/Pnale-Android-0.4.0.apk`, version 0.4.0 / code 4, Android 16+, debug v2 signature verified.
SHA-256: `9b64cc23bb06955e706ebe1db25e670db025f84642d30c8a715570cd63b7d198`.

## Verified

- Build, lint and all 11 unit tests pass (8 market-core + 3 display correctness tests). New checks cover price rounding/carry, tiny positive price preservation at supported quote precision, separate coin/price/change content and stale/reconnecting chip indication.
- On disposable read-only `emulator-5556` with real Binance data, focused style instrumentation reports [PNALE_STYLES_PASS](validation/styles-smoke.txt). Actual StyleActivity controls persist glass/grid/palette/font and change all four chip layouts; ETH/BTC/SOL/ZEC quotes are fresh. Shared engine selection changes to ZEC and the posted foreground notification retains the promotion request.
- Native small icon visibly reads **ZEC** in the [chip-layout editor screenshot](validation/style-chip-layouts.png). The four large capsules in that screenshot are explicitly labeled content previews, not evidence that AOSP promoted four notifications.
- [Glass four-coin board](validation/style-glass-grid.png) visually checked after dismissing the OS first-time immersive tutorial; no crop/overlap. Manual UI taps selected four-grid/white/light and hid controls. [Style editor](validation/style-editor-glass.png) shows the live preview and options. `style-glass-row.png` retains the earlier run's immersive tutorial overlay and is not a clean appearance acceptance image.
- Changing style does not remove BigTextStyle, ongoing or the existing promotion extra. No custom RemoteViews or fake activity category added. Glass uses drawn translucent gradients/highlight edges, not system-wide live blur/refraction.

## User-confirmed behavior and remaining checks

- User explicitly reports lockscreen, charging screensaver and Fluid Cloud all display on their ColorOS with 0.3.0; their screenshot showed duplicate numeric icon/price. 0.4.0 changes the default left icon to the coin symbol and offers alternative content layouts. The final OEM capsule layout still needs that user's phone check.
- No OPPO physical device connected during this run; 0.4.0 ColorOS shell material/size, charging-idle activation, long background power use, extreme font/orientation matrix and the newly glass-styled optional floating window were not independently device-verified. The shared board's rendering was verified in the desk/preview, not a second DreamService lifecycle run.
- System lockscreen/capsule fonts and shell remain OEM-controlled. Glass background is for the app-rendered board and optional overlay; do not label the system capsule as iOS Liquid Glass.
- Test uses a disposable emulator and changes its style/selected-coin/watchlist state; do not run `-e styles true` on the user's phone. The temporary proxy was restored and emulator shut down after checks. No proxy is embedded in the APK.
- Universal external push delivery/server is only discussed as a future extension; this version does not implement a remote push API.

## Focused reproduction on a disposable test device

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline --console=plain --max-workers=1
# Install both APKs; allow app notifications via the test device's UI.
adb -s emulator-5556 shell am instrument -w -e styles true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

# Historical 0.3.0 validation — 2026-09-20

Historical artifact: `../dist/Pnale-Android-0.3.0.apk`, 0.3.0 / code 3, minimum API 36; debug APK with verified v2 signature.
SHA-256: `db35d695bb86fbb374feb919b71637a1b139c762663368fff6456e2c0c8728bf`.

## Verified in this version

- Build, 8 unit tests and lint (0 errors, 13 warnings) passed. Final app + instrumentation installed on temporary read-only `emulator-5556`, Android 16. Explicit final `PNALE_DISPLAYS_PASS` in [display smoke](validation/displays-smoke.txt).
- Actual posted notification PUBLIC/SECRET toggle; documented Live Updates request extra present. Actual `FLAG_PROMOTED_ONGOING=false` on this build: **no capsule promotion proven**. Actual Unpin PendingIntent clears preference/request; Delete PendingIntent stops foreground service and removes notification.
- Real DeskActivity displays live ETH/BTC prices. Controls show/hide; exit releases market ownership and pauses the engine when no other owner remains. [Prices](validation/desk-prices.png), [controls](validation/desk-controls.png).
- System screensaver Settings listed and selected `QuoteDreamService`; Preview started it through the OS. [Dream state](validation/dream-state.txt) confirms Pnale, `isPreview=true`, `canDoze=false`; [live screenshot](validation/dream-preview.png) shows ETH/BTC. Touch exits; [exited state](validation/dream-exited.txt). Automatic charging-idle activation was not waited for or proven. The root-only `cmd dreams start-dreaming` command was unavailable; OS Settings Preview was used instead.
- Transparent numeric overlay visibly renders inside the status-bar region over the launcher; [home screenshot](validation/top-price-home.png). Windows remain below SystemUI. Settings permission page hides the overlay. Status-bar gestures intercept dragging; added in-app position sliders. Actual UI taps persisted x=576,y=40 and moved the price; default initial y=38 aligns a 28dp scaled window with this emulator's status bar. [Window state](validation/window-top-price.txt).
- Earlier DeskActivity launch crash from accessing insets before decor creation was fixed; final instrumentation passes the real activity launch and lifecycle.

## Remaining boundaries

- No physical OPPO / ColorOS device connected. Native Fluid Cloud permission, AOD, theme engine and long Doze/battery behavior remain unverified. Live Updates request is experimental/default off, with 36.1 extra support dependent on OS build and OEM rules.
- This emulator has no enabled keyguard (`showing=false` after sleep/wake). PUBLIC/SECRET notification behavior was verified through the notification object, not an actual secure-lock-screen screenshot. `wake-no-keyguard.png` is a home-screen wake capture, not lock-screen evidence.
- Multi-color engine official document 12221 returned `300001 无权限查看该文档`; no current data-binding contract/template or OPPO test device. No functional theme package claimed. See [API investigation](OPPO_API.md).
- Real network checks used existing host proxy `10.0.2.2:7897` in this temporary emulator only; no proxy in APK. Screensaver/desk are illuminated display modes, not true dozing AOD. Brightness, rotation/large-font matrix and unattended charging activation need additional device checks.
- Prior 0.2.0 images/tests below remain historical; they were not relabeled as a fresh complete regression run.

## Reproduce focused checks

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline --console=plain --max-workers=1
# On an explicitly selected test device, install both APKs and enable notifications.
# Optional top=true additionally requires overlay permission from system Settings.
adb -s emulator-5556 shell am instrument -w -e displays true -e top true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

# Historical 0.2.0 validation — 2026-09-20

Previous artifact (replaced by 0.3.0): version 0.2.0 / code 2, minimum Android 16 / API 36, debug signature.

SHA-256: `8d05c6bd079fc58b3c0c6e58d271f2d1212f31631b0cb6bec37e434c2217b868`

## Passed

- Debug app and instrumentation APK builds succeeded; 8 core unit tests passed. Lint completed with 0 errors and 13 warnings. APK v2 signature verified with Android Build Tools 36.0.0.
- Installed and tested on a temporary read-only Android 16 emulator, explicitly selected serial `emulator-5556`. No physical phone was connected.
- Real Binance snapshots and WS ticker/candle events for ETH/BTC, 60 candles, rapid period/coin switches, contiguous BTC hourly candles, reconnect/backfill, typed input, persistence and invalid-symbol protection.
- **Notification-only operation, without overlay permission**: foreground service starts, notification includes ETH/BTC, WS timestamps continue advancing after the activity goes to the background, real notification PendingIntents switch the coin and stop the service, notification disappears and shared engine pauses. See [notification smoke](validation/notification-smoke.txt).
- Dynamic bitmap price icon visually confirmed in the [home-screen status bar](validation/status-price.png); pure decimal digits, no symbol letters or K/M abbreviation. [Expanded notification](validation/notification.png) shows full prices and changes.
- Overlay permission granted only in the temporary emulator for the optional-window tests. Real system overlay starts; collapse, expand and stop service checks pass. See [overlay smoke](validation/overlay-smoke.txt).
- Manual drag moved the actual window from x=16,y=180 to x=404,y=678; settings persisted.
- Default compact overlay uses 80% size and 80% opacity, observed at 484×119 physical pixels on the emulator's 420-dpi display. [Default overlay](validation/overlay-default.png).
- Through the actual activity sliders, changed size to 65% and opacity to 50%; WindowManager reports a 393×97-pixel overlay and alpha=0.5. [Window evidence](validation/window-resized.txt), [resized overlay](validation/overlay-resized.png).
- Expanded through the scaled window's real tap target; chart and controls visible at 65%/50%. [Expanded overlay](validation/overlay-expanded.png). Tapped the window's `+` button: persisted scale became 70% and actual expanded dimensions became 626×887 pixels, confirming scaled touch coordinates work.
- Tapped the window `×`: overlay removed while the foreground notification service remained running.

## Boundaries

- Network tests used the Mac's existing proxy through emulator host address `10.0.2.2:7897`; no proxy is embedded in the APK. Direct phone connectivity still depends on the user's network.
- The status-bar digit icon is small, especially with many integer or decimal places. The screenshot proves rendering on this emulator, not readability on every OPPO display or status-bar setting.
- No physical ColorOS device verification, long-run battery/Doze/lock-screen assurance, rotation/large-font matrix or maximum-30-coin UI test.
- ColorOS Fluid Cloud / Android Live Updates are **researched, not implemented or validated** in 0.2.0. See [official API findings](OPPO_API.md). Ordinary numeric notification icons must not be described as Fluid Cloud.
- This build replaces 0.1.0; old `validation/activity.png`, `editor.png` and `market-smoke.txt` are retained as prior-version evidence only.

## Reproduce

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Enable app notifications first; leave overlay permission OFF for this test.
adb -s DEVICE_SERIAL shell am instrument -w -e notifications true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
# Optional separate run after enabling overlay permission:
adb -s DEVICE_SERIAL shell am instrument -w -e overlay true app.pnale.ticker.test/app.pnale.ticker.MarketSmokeInstrumentation
```

Read the `PNALE_SMOKE_PASS` / `PNALE_SMOKE_FAIL` marker, because `am instrument` can exit with shell code 0 even when the instrumentation reports failure. Input tests use temporary preferences; notification actions may change the app's selected coin, and overlay checks change compact state.
