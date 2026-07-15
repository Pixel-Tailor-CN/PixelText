# 短信通知图标选择 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为短信通知增加四种可持久化选择的内置小图标，并保持 Smartspacer 等非短信场景不变。

**Architecture:** 以领域层内置目录提供稳定 ID 到 drawable 的映射，DataStore 保存 ID，设置 UI 通过独立页面修改选择；短信通知创建与回复状态更新统一解析当前资源。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、DataStore Preferences、Android VectorDrawable、Koin

## Global Constraints

- 仅支持 Android 12+，最低 SDK 31，JVM target 21。
- UI 使用 Jetpack Compose 和 Material 3，不新增 XML layout。
- 不新增单元测试、测试依赖或网络依赖。
- 文档与代码注释使用中文，日志使用英文。
- Smartspacer 与非短信通知保持现有图标行为。

---

### Task 1: 图标目录与设置持久化

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/SmsNotificationIcon.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`

**Interfaces:**
- Produces: `SmsNotificationIcon.entries`、`SmsNotificationIcon.fromId(String?)`、`AppSettings.smsNotificationIconId`、`setSmsNotificationIconId(String)`。

- [ ] 定义四种稳定 ID、显示名称和 drawable 映射，未知 ID 回退到双气泡。
- [ ] 增加 DataStore key、同步读取与设置状态 Flow 映射。
- [ ] 在 ViewModel 暴露设置方法。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 2: 本地矢量资源与短信通知接入

**Files:**
- Reuse: `app/src/main/res/drawable-anydpi/ic_notification_sms.xml`
- Create: `app/src/main/res/drawable/ic_sms_notification_single_bubble.xml`
- Create: `app/src/main/res/drawable/ic_sms_notification_envelope.xml`
- Create: `app/src/main/res/drawable/ic_sms_notification_text_bubble.xml`
- Create: `docs/icon-sources.md`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/notification/SmsNotificationHelper.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/receiver/NotificationActionReceiver.kt`

**Interfaces:**
- Consumes: `SmsNotificationIcon.fromId(String?).drawableRes`。
- Produces: 所有短信通知及回复状态更新使用当前选择；原 `ic_notification_sms` 不变。

- [ ] 将当前与旧版图标整理为短信专用 VectorDrawable。
- [ ] 参考 iconfont 候选类别在项目内绘制信封和文本气泡，生成符合通知小图标要求的单色 VectorDrawable，并记录来源。
- [ ] 在短信通知创建和回复状态更新处读取当前图标。
- [ ] 搜索 `ic_notification_sms` 调用，确认 Smartspacer 和非短信通知未被改动。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 3: Compose 选择页面与导航

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SmsNotificationIconSettingsScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`

**Interfaces:**
- Consumes: `AppSettings.smsNotificationIconId`、`SettingsViewModel.setSmsNotificationIconId(String)`、内置图标目录。
- Produces: 设置入口摘要、两列可选卡片、立即保存和返回导航。

- [ ] 增加设置入口与独立路由。
- [ ] 使用 Material 3 两列卡片展示图标、名称和选中状态。
- [ ] 点击卡片立即持久化，未知值在 UI 中展示默认项。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 4: 最终验证

**Files:**
- Verify: `app/src/main/`
- Verify: `docs/`

- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期 `BUILD SUCCESSFUL`。
- [ ] 运行 `./gradlew :app:lintDebug`，预期无新增错误。
- [ ] 检查四种图标在设置页可见、默认选中双气泡、选择后下一条短信通知生效。
- [ ] 检查 Smartspacer、垃圾扫描和资源更新仍使用原有图标。
