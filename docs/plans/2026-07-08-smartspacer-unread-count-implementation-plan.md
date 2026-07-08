# Smartspacer 未读数量统计范围实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Smartspacer 未读数量增加按正常短信、骚扰短信、归档短信配置统计范围的能力，并保持相关状态变化后实时刷新。

**Architecture:** 在应用设置中新增 Smartspacer 统计范围配置，`SmartspacerSmsRepository` 读取未读短信基础集合后，结合骚扰库和归档库按消息级并集去重统计。设置页通过单独首选项和弹窗编辑配置，相关的骚扰识别与归档动作统一触发 Smartspacer 刷新。

**Tech Stack:** Kotlin、Jetpack Compose、SharedPreferences、Room、Android Telephony、Koin

## Global Constraints

- UI 必须使用 Jetpack Compose，不新增 XML layout。
- 代码注释和文档使用中文，日志打印使用英文。
- 不新增单元测试、不新增测试依赖、不运行单元测试任务。
- 修改后优先使用 `./gradlew :app:compileDebugKotlin` 验证。

---

### Task 1: 扩展设置模型与存储

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `SmartspacerUnreadCountSettings`
- Produces: `AppSettings.smartspacerUnreadCountSettings`
- Produces: `AppSettingsRepository.setSmartspacerUnreadCountSettings(settings: SmartspacerUnreadCountSettings)`
- Produces: `AppSettingsRepository.getSmartspacerUnreadCountSettings(): SmartspacerUnreadCountSettings`

- [ ] 新增 `SmartspacerUnreadCountSettings` 数据模型，默认三项都为 `true`
- [ ] 为设置仓库增加 3 个布尔键的读写逻辑
- [ ] 在 `SettingsViewModel` 增加设置保存入口，并在保存后通知 `Smartspacer` 刷新
- [ ] 更新 Koin 注入，向 `SettingsViewModel` 传入应用上下文

### Task 2: 重构 Smartspacer 未读统计

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/source/TelephonyDataSource.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/smartspacer/SmartspacerSmsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `TelephonyDataSource.getUnreadSmsMessagesForSmartspacer(): List<...>`
- Produces: `SmartspacerSmsRepository.getUnreadSmsCount(): Int`

- [ ] 新增 Smartspacer 未读短信基础查询，返回消息 ID 和会话 ID
- [ ] 为 `SmartspacerSmsRepository` 注入设置仓库、骚扰仓库和归档数据库
- [ ] 按“正常/骚扰/归档”并集规则实现未读数量统计
- [ ] 保持 3 个开关全关时返回 `0`

### Task 3: 补齐 Smartspacer 刷新联动

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/worker/SpamDetectionWorker.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/worker/HistoricalSpamScanWorker.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`

**Interfaces:**
- Produces: 相关骚扰识别、手动标记、归档变更后的 `SmartspacerIntegration.notifyChanged(...)`

- [ ] 在新短信骚扰识别完成后统一刷新 `Smartspacer`
- [ ] 在历史骚扰扫描完成后刷新 `Smartspacer`
- [ ] 在手动标记短信为骚扰或非骚扰后刷新 `Smartspacer`
- [ ] 在归档和取消归档后刷新 `Smartspacer`

### Task 4: 增加设置页入口与弹窗

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Produces: `SmartspacerUnreadCountSettings.preferenceSummary(): String`
- Produces: `SmartspacerUnreadCountSettingsDialog`

- [ ] 在设置页“应用功能”分类下新增 `Smartspacer 未读统计范围` 首选项
- [ ] 根据当前配置显示动态摘要
- [ ] 新增包含 3 个开关的弹窗，支持确认保存和取消回退

### Task 5: 编译验证

**Files:**
- Verify only

**Interfaces:**
- Consumes: 前 4 个任务的全部修改

- [ ] 运行 `./gradlew :app:compileDebugKotlin`
- [ ] 修复编译错误直到通过
- [ ] 记录本次验证结果与未覆盖风险
