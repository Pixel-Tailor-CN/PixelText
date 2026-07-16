# 验证码短信自动删除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加默认关闭、保留期限可配置的验证码 SMS 自动删除功能，并每 5 小时通过 WorkManager 清理一次。

**Architecture:** 设置由 `AppSettingsRepository` 持久化，`VerificationCodeCleanupScheduler` 管理唯一周期任务，`VerificationCodeCleanupWorker` 调用验证码仓库删除过期 SMS 和索引。设置页负责受控数字输入与错误提示，应用启动和设置变更都会同步任务状态。

**Tech Stack:** Kotlin、Jetpack Compose、Room、Koin、WorkManager、Telephony ContentProvider。

## Global Constraints

- 功能默认关闭；保留天数默认 7，范围 `1–365`。
- 仅删除验证码索引对应的 SMS，不处理 MMS。
- 周期执行间隔固定为 5 小时。
- 不记录短信正文、验证码等敏感内容，日志使用英文短语。
- 按项目约定不新增或运行单元测试，以编译、Lint 和模拟器验证替代。

---

### Task 1: 设置模型与持久化

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`

**Interfaces:**
- Produces: `setVerificationCodeAutoDeleteEnabled(Boolean)`、`setVerificationCodeRetentionDays(Int)`、对应同步读取方法和 `AppSettings` 字段。

- [ ] 添加 SharedPreferences key、默认值、最小值和最大值常量。
- [ ] 在仓库实现中读取和保存配置，写入天数时使用 `coerceIn(1, 365)`。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 2: 过期验证码清理数据流

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/db/VerificationCodeIndexDatabase.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/repository/VerificationCodeRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/VerificationCodeRepositoryImpl.kt`

**Interfaces:**
- Produces: `suspend fun deleteExpiredMessages(cutoffTimestamp: Long): VerificationCodeCleanupResult`。

- [ ] 在 DAO 中按活动 generation 和 `timestamp < cutoffTimestamp` 查询过期索引。
- [ ] 逐批调用 `TelephonyDataSource.deleteMessages` 删除 SMS，再查询仍存在的 ID。
- [ ] 只清理已不存在的 SMS 对应索引，并返回扫描、删除和失败数量。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 3: 周期 Worker 与调度器

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/VerificationCodeCleanupWorker.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/VerificationCodeCleanupScheduler.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/PixelTextApp.kt`

**Interfaces:**
- Produces: `sync()`，根据当前设置注册或取消唯一 5 小时周期任务。

- [ ] Worker 重新读取开关及规范化后的天数，计算严格截止时间并调用仓库。
- [ ] Scheduler 使用 `enqueueUniquePeriodicWork` 和 `ExistingPeriodicWorkPolicy.UPDATE`。
- [ ] 在 Koin 注册 Scheduler，并在应用启动调用 `sync()`。
- [ ] 对可恢复异常返回 `Result.retry()`，关闭功能返回 `Result.success()`。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 4: 设置页面交互

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Consumes: 设置仓库及 `VerificationCodeCleanupScheduler.sync()`。
- Produces: 开关 setter 和返回 `Boolean` 的保留天数 setter。

- [ ] 开关变化后持久化并立即同步调度。
- [ ] 天数 setter 只接受 `1–365`，成功后持久化并在开启状态更新周期任务。
- [ ] 设置页增加开关；开启时通过 `AnimatedVisibility` 显示数字输入框。
- [ ] 输入框使用数字键盘，只保留数字字符；空值及越界值显示错误且不保存。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 5: 集成验证

**Files:**
- Verify all modified files.

- [ ] 运行 `./gradlew :app:compileDebugKotlin :app:lintDebug :app:installDebug`，预期全部成功。
- [ ] 模拟器确认默认关闭、开启后显示 7 天、非法输入不能保存、关闭后输入项隐藏。
- [ ] 使用 `adb shell dumpsys jobscheduler` 或 WorkManager 数据检查确认唯一周期任务存在且间隔为 5 小时，关闭后任务被取消。
- [ ] 运行 `git diff --check` 并检查工作区范围。
