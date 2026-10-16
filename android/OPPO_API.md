# ColorOS 流体云官方接口核查

核查时间：2026-09-20。当前 APK 0.2.0 已实现数字通知图标和可缩放透明浮窗；**尚未集成或验证 ColorOS 流体云**。

## OPPO 原生端侧方案

官方文档：[接入准备](https://open.oppomobile.com/documentation/page/info?id=13572)、[意图共享（端侧）](https://open.oppomobile.com/documentation/page/info?id=13558)、[意图共享数据结构](https://open.oppomobile.com/documentation/page/info?id=13565)。官方网页采用动态加载，本次通过其公开页面使用的 `/oneoppoapi/doc/detail` 文档接口读取了正文。

- 接入准备要求先明确服务形态、请求频率、包名等并联系 OPPO；首次接入需开放平台账号及企业认证。
- 本地 API：`contentResolver.acquireUnstableContentProviderClient("IntelligentIntent")`，再调用 `ContentProviderClient.call("shareIntent", null, extras)`。
- extras 的 `intentData` 为 JSON；包含 `intentName`、`identifier`、`timestamp`、`serviceId`、`intentAction`、`intentEntity` 等结构。
- `intentAction.actionStatus`：0 创卡、1 更新、2 销卡；`intentEntity.capsule` 承载胶囊信息。
- 返回 Bundle 中 `result` JSON 的 `code` 为 0 才表示成功；官方明确列出 `10101001` 无共享权限、`10101002` 权限过期、`10103002` 频次超限等错误。
- 普通自签名 APK 未取得接入资格不能保证调用成功；行情垂域、刷新频率、卡片标识仍需 OPPO 确认。

## Android 官方 Live Updates

官方文档：[Create live update notifications](https://developer.android.com/develop/ui/views/notifications/live-update)、[Notification.Builder](https://developer.android.com/reference/android/app/Notification.Builder)、[Notification](https://developer.android.com/reference/android/app/Notification)。

- 状态栏胶囊文字：`Notification.Builder.setShortCriticalText(String)`，API 36，官方建议最长约 7 字符，并不保证全部显示。
- 提升通知需要 `POST_PROMOTED_NOTIFICATIONS`、ongoing、contentTitle 和标准通知样式（可用当前的 BigTextStyle），不能使用自定义 RemoteViews；channel 不能为 IMPORTANCE_MIN。
- 请求接口：`NotificationCompat.Builder.setRequestPromotedOngoing(true)`，或官方 extras 键。原生 `Notification.Builder.setRequestPromotedOngoing` / `EXTRA_REQUEST_PROMOTED_ONGOING` 的公开 SDK 文档标记为 **36.1**；当前本机 API 36 SDK 没有这两个符号，不能把所有 Android 16 构建视为完全相同。
- 系统状态查询：`NotificationManager.canPostPromotedNotifications()`；通知资格检查 `Notification.hasPromotableCharacteristics()`；真正提升后系统设置 `FLAG_PROMOTED_ONGOING`。
- 适用有明确开始/结束、由用户启动、时间敏感的活动。长期环境信息并非默认合适场景；我们的盯盘应建模为用户主动开启并可停止的一次观测，是否符合具体设备准入仍未验证。
- 厂商可增加额外准入条件；接口请求成功或本地编译通过不能证明 ColorOS 已显示流体云。

[ColorOS 设计负责人公开说明](https://weibo.com/1645677583/Q9z17B08G)提到与 Google 共创双兼容方案，支持原生路线的方向；这不是当前 APK 在用户具体机型上的验收证据。

## 实施判断

先在用户 ColorOS 16 真机核实具体系统版本与提升通知设置，优先试 Android 官方 Live Updates。OPPO 原生接口作为有正式接入资格时的路线。当前保留已验证的数字状态栏通知和小浮窗；不伪装导航/媒体、不绕过 OPPO 权限检查。

## 0.3.0 实验实现（2026-09-20）

- APK 已实现 Android 官方 Live Updates 请求：manifest `POST_PROMOTED_NOTIFICATIONS`、标准 BigTextStyle、ongoing、`android.requestPromotedOngoing=true` 与 `setShortCriticalText`。该 extra 官方标为 36.1，早期 Android 16 可忽略；主界面读取已发布通知的 `FLAG_PROMOTED_ONGOING`，不把请求成功当作流体云成功。超过 7 字符的价格不截断成错误数值，胶囊文字留空，完整价格仍在通知。
- 实验开关默认关闭；通知有取消胶囊、停止入口，系统删除通知回调停止服务。持续行情不保证符合系统/OEM 的提升条件。本 APK 不冒用导航、配送、通话或媒体类别。
- 亮屏锁屏通知默认 PUBLIC，应用开关可改为 SECRET；用户的系统通知/锁屏设置仍优先。
- 实现官方 DreamService 充电屏保和独立大字 Activity。二者都使用真实行情、过期标识，黑底缓慢水平移动数字；屏保由系统选择和启动。并非 ColorOS 原生熄屏 AOD。
- 顶部纯价格使用 TYPE_APPLICATION_OVERLAY + LAYOUT_IN_SCREEN + fitInsetsTypes(0)，背景透明、仅数字、可拖动。官方明确其层级低于状态栏，因此只能尝试从透明区域显示，无法保证覆盖系统背景或图标。参考 [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)。

### 多彩引擎/主题包边界

用户提出的主题组件路线有独立工具链。查到的 [官方调试文档入口 12221](https://open.oppomobile.com/new/developmentDoc/info?id=12221) 在本次匿名读取返回 `code=300001, 无权限查看该文档`（原始响应保留于 `.runtime/oppo-12221.json`）。尚未取得当前引擎模板、安装包、ColorOS 16 数据绑定/权限规范，未核实 ContentProviderBinder 对自定义行情 Provider 的支持，故未生成声称可运行的主题包，也未导出未验证的数据 Provider。需官方可访问规范及装有引擎的 OPPO 真机才能继续这一分支。原生流体云企业准入要求仍同上。


## 0.4.0 用户反馈与布局适配

用户明确反馈其 ColorOS 上锁屏、充电屏保、流体云都能展示，并提供了 0.3.0 胶囊截图：左边是数值通知图标，右边是价格正文。这是用户真机反馈，不是本地模拟器的提升标志证据。

0.4.0 默认将实时活动的 smallIcon 渲染为当前币种（如 ZEC），`setShortCriticalText` 继续使用价格；可选币种+涨跌、价格+涨跌、图标+价格。普通通知模式仍遵守原状态栏数字/仅图标设置。保持 BigTextStyle 与提升字段，不使用自定义 RemoteViews。新布局最终在 ColorOS 的表现仍需用户检查。

玻璃风格只应用于 App 自绘屏保、大字页和浮窗；不能改写系统胶囊的材质。依据：[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。
