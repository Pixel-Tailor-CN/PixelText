# 通知快捷操作配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为短信通知新增一个可配置的快捷操作系统，允许用户在设置页自定义 `已阅`、`验证码复制`、`回复` 这三个按钮的顺序与文案。

**Architecture:** 在现有 `SharedPreferences + AppSettingsRepository + SettingsViewModel + Compose 设置页` 结构上扩展一套固定三项的通知快捷操作配置。通知构建逻辑继续留在 `SmsNotificationHelper` 中，但其 action 顺序与文案改为由设置驱动；配置页面使用本地草稿态和手动保存，未保存时拦截返回。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Koin、SharedPreferences、Android NotificationCompat

## Global Constraints

- 使用中文回复，生成的代码注释和文档使用中文，日志打印使用英文。
- UI 必须使用 Jetpack Compose，不新增 XML layout 文件。
- 优先沿用现有架构、命名和包边界。
- 依赖注入继续使用 `di/AppModule.kt` 中现有的 `SettingsViewModel`。
- 不引入第三方拖拽依赖，使用 Compose 现有能力完成交互。
- 本项目不做单元测试；除非用户明确要求，否则不要新增 `app/src/test/`、`app/src/androidTest/` 测试代码，不要新增测试依赖，也不要运行 `test` 相关任务。
- 验证优先使用编译、Lint、Mock 界面和手动检查。

---

### Task 1: 扩展通知快捷操作设置模型

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/NotificationQuickActionSettings.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `AppSettingsRepository`, `AppSettings`, `SettingsViewModel`
- Produces:
  - `enum class NotificationQuickActionType`
  - `data class NotificationQuickActionConfig`
  - `fun List<NotificationQuickActionConfig>.normalizeNotificationQuickActionConfigs(): List<NotificationQuickActionConfig>`
  - `fun NotificationQuickActionConfig.renderLabel(code: String): String`
  - `fun NotificationQuickActionConfig.validationError(): String?`
  - `fun AppSettingsRepository.setNotificationQuickActionConfigs(configs: List<NotificationQuickActionConfig>)`

- [ ] 新建通知快捷操作领域模型文件，封装默认值、模板渲染、文案校验和排序标准化逻辑。
- [ ] 在 `AppSettings` 中加入 `notificationQuickActionConfigs` 字段，并为 `AppSettingsKeys` 增加 3 个 action 的文案键和顺序键。
- [ ] 在 `AppSettingsRepository` 中增加读取与批量保存通知快捷操作配置的接口。
- [ ] 在 `AppSettingsRepositoryImpl` 中实现新配置的读取、标准化和持久化。
- [ ] 在 `SettingsViewModel` 中暴露 `setNotificationQuickActionConfigs()` 供配置页保存。
- [ ] 通过代码自检确认默认值、空值回退和顺序归一化逻辑完整闭环。

### Task 2: 让通知 action 使用配置驱动的顺序与文案

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/notification/SmsNotificationHelper.kt`

**Interfaces:**
- Consumes:
  - `NotificationQuickActionType`
  - `NotificationQuickActionConfig`
  - `normalizeNotificationQuickActionConfigs()`
  - `renderLabel(code: String)`
- Produces:
  - 配置驱动的通知 action 构建顺序
  - 普通通知和锁屏公开版本通知统一的排序规则

- [ ] 在 `SmsNotificationHelper` 中读取通知快捷操作配置，并按类型建立配置映射。
- [ ] 把 `已阅`、`回复`、`复制验证码` 三个 action 的标题改为读取用户配置。
- [ ] 把通知 action 添加逻辑改为“按配置顺序遍历可用 action”而不是硬编码顺序。
- [ ] 对普通短信场景只保留 `mark_read` 和 `reply`，保持其在总排序中的相对顺序。
- [ ] 对锁屏公开版本通知复用同一排序机制，但显式过滤 `copy_code`。
- [ ] 检查现有验证码开关、锁屏隐藏开关和回复 RemoteInput 提示逻辑，确保行为不回归。

### Task 3: 增加设置入口与导航

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`

**Interfaces:**
- Consumes:
  - `settings.notificationQuickActionConfigs`
  - `NotificationQuickActionConfig`
- Produces:
  - `onNavigateToNotificationActions: () -> Unit`
  - 新 route：`notification_actions`

- [ ] 在 `SettingsScreen` 的“应用功能”分组新增 `通知快捷操作` 设置项。
- [ ] 为该设置项添加摘要文案，展示当前排序后的按钮模板。
- [ ] 为 `SettingsScreen` 增加 `onNavigateToNotificationActions` 参数并从 `AppNavigation` 传入导航行为。
- [ ] 在 `AppNavigation` 中新增 `notification_actions` route，并挂载新的配置页面。
- [ ] 检查现有 `settings`、`swipe_actions`、`sample_submission` 路由风格，保持一致的导航过渡和返回行为。

### Task 4: 实现通知快捷操作配置页

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/NotificationActionSettingsScreen.kt`

**Interfaces:**
- Consumes:
  - `SettingsViewModel.settings`
  - `SettingsViewModel.setNotificationQuickActionConfigs(configs: List<NotificationQuickActionConfig>)`
  - `NotificationQuickActionType`
  - `NotificationQuickActionConfig`
  - `validationError()`
  - `renderLabel(code: String)`
- Produces:
  - 新的二级设置页面
  - 本地草稿态编辑
  - 拖拽排序
  - 未保存返回拦截

- [ ] 新建 `NotificationActionSettingsScreen`，使用 `Scaffold + TopAppBar + LazyColumn` 组织页面。
- [ ] 从 `settings.notificationQuickActionConfigs` 初始化本地草稿列表，按当前顺序展示。
- [ ] 绘制说明卡片和验证码通知预览，预览中的 `copy_code` 使用示例码 `123456`。
- [ ] 为每个 action 项绘制名称、说明、单行输入框和拖拽手柄。
- [ ] 使用 Compose 手势实现“长按拖拽手柄后上下重排”。
- [ ] 对每个输入框实时计算校验结果，并在 UI 中展示错误状态。
- [ ] 右上角 `保存` 按钮执行校验，通过后批量保存并返回上一页。
- [ ] 使用 `BackHandler` 和顶部返回按钮复用同一离开逻辑；当草稿未保存时弹出三选项提示。
- [ ] 当草稿无效时，离开弹窗中的 `保存` 按钮禁用。

### Task 5: 编译验证与人工回归检查

**Files:**
- No code changes required unless verification exposes issues.

**Interfaces:**
- Consumes: 全部前置任务的实现
- Produces: 可交付的验证结果与必要修正

- [ ] 运行 `./gradlew :app:compileDebugKotlin`，确认新增页面、设置模型和通知逻辑可以通过 Kotlin 编译。
- [ ] 如编译报错，逐项修复缺失导入、类型不匹配、Compose API 使用问题和 route 接线问题。
- [ ] 完成静态回归检查：
  - `SettingsScreen` 新入口摘要正确
  - `NotificationActionSettingsScreen` 保存与返回逻辑闭环
  - `SmsNotificationHelper` 普通短信 / 验证码短信 / 锁屏公开版本的 action 顺序正确
- [ ] 整理最终说明，明确本次未做真机通知验证和未新增单元测试。
