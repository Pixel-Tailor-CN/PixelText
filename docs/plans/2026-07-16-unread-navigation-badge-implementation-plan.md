# 底部导航未读消息徽标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在“会话”底部导航按钮显示可配置的未读 SMS 总数徽标，并与 Smartspacer 共用底层计数逻辑。

**Architecture:** 新增共享 `UnreadSmsCounter`，使用系统未读 SMS、归档表和骚扰结果执行可配置过滤。新增 `UnreadBadgeViewModel` 监听 SMS ContentProvider、归档与骚扰 Room Flow 以及 App 设置，向 `HomeScreen` 提供实时计数。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Coroutines Flow、Room、ContentObserver、Koin。

## Global Constraints

- 只统计 `READ = 0` 的收件箱 SMS，不统计 MMS。
- App 徽标始终排除归档会话。
- “隐藏完全骚扰对话”开启时排除完全骚扰会话，关闭时计入。
- “未读红点提醒”默认开启；`0` 隐藏，`1–99` 显示数字，超过 `99` 显示 `99+`。
- 不新增数据库字段或迁移，不记录短信正文。
- 项目不新增或运行单元测试，以编译、Lint 和模拟器验证替代。

---

### Task 1: 设置持久化

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Produces: `AppSettings.unreadBadgeEnabled` 和 `setUnreadBadgeEnabled(Boolean)`。

- [ ] 增加默认值为 `true` 的设置 key、同步读取与写入方法。
- [ ] 在设置页“应用功能”分类增加“未读红点提醒”开关。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 2: 共享未读 SMS 计数器

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/model/UnreadSmsCountFilter.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/UnreadSmsCounter.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/db/ConversationArchiveDatabase.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/db/SpamDatabase.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/SpamRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/smartspacer/SmartspacerSmsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `suspend fun count(filter: UnreadSmsCountFilter): Int`。
- Produces: 归档和骚扰表变化 Flow，供 App 触发重新计数。

- [ ] 计数器读取系统未读 SMS，并按普通、骚扰、归档和完全骚扰会话条件过滤。
- [ ] Smartspacer 改为调用共享计数器，保持现有配置语义。
- [ ] 为归档表和骚扰表增加只读 Flow 查询，不修改 schema。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 3: App 实时计数状态

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/UnreadBadgeViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `StateFlow<Int> unreadCount`。

- [ ] 使用 `callbackFlow` 监听 `Telephony.Sms.CONTENT_URI`。
- [ ] 合并短信变化、归档 Flow、骚扰 Flow 和 App 设置 Flow，并使用 `mapLatest` 在 IO 线程重新计数。
- [ ] 查询失败时记录英文日志并保留最近一次成功结果。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 4: 底部导航徽标

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/HomeScreen.kt`

**Interfaces:**
- Consumes: `UnreadBadgeViewModel.unreadCount` 与 `AppSettings.unreadBadgeEnabled`。

- [ ] 使用 Material 3 `BadgedBox` 和 `Badge` 包裹“会话”图标。
- [ ] 根据设置和数量执行 `0` 隐藏、`1–99`、`99+` 展示规则。
- [ ] 保持导航选择、滚动隐藏动画和无障碍描述不变。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译通过。

### Task 5: 集成验证

**Files:**
- Verify all modified files.

- [ ] 运行 `./gradlew :app:compileDebugKotlin :app:lintDebug :app:installDebug`，预期成功。
- [ ] 模拟器验证设置默认开启、关闭即时隐藏、重新开启恢复。
- [ ] 验证普通数量及 `99+` 文案，无未读时隐藏。
- [ ] 验证归档短信不计数、完全骚扰会话跟随设置、MMS 不计数。
- [ ] 运行 `git diff --check` 并检查工作区范围。
