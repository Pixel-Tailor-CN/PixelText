# 验证码视图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加只索引 SMS 的验证码聚合视图，并把会话与验证码组织为带底部导航的首页子页面。

**Architecture:** 使用独立 Room 数据库保存无正文的验证码代际索引，WorkManager 串行完成增量校验和完整重建，新短信即时写入索引。首页容器通过嵌套 NavHost 承载会话与验证码页面，共享会随滚动方向展开/收起的开始聊天按钮。

**Tech Stack:** Kotlin、Room、WorkManager、Coroutines/Flow、Jetpack Compose、Material 3、Navigation Compose、Koin、Android Telephony ContentProvider

## Global Constraints

- 仅识别 SMS，不读取或解析 MMS。
- 索引不保存原始短信正文，正文只在用户切换文本模式时按消息 ID 查询 ContentProvider。
- 验证码展示全部历史结果，按月份倒序分页，月份标题吸顶。
- 规则版本变化时完整重建，新短信按当前规则即时更新。
- 首页底栏只包含“会话”和“验证码”，二级页面不显示底栏。
- 两个首页子页面共享“开始聊天”扩展按钮，向下滚动收起、向上滚动展开。
- 不实现验证码定时删除，不新增网络依赖或测试依赖。
- 项目不新增或运行单元测试；验证使用 Kotlin 编译、Lint 和人工检查。
- 代码注释与文档使用中文，日志使用英文且以小写短语开头。

---

### Task 1: 验证码索引数据库与仓库

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/db/VerificationCodeIndexDatabase.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/model/VerificationCodeIndexModel.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/repository/VerificationCodeRepository.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/VerificationCodeRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/source/TelephonyDataSource.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `VerificationCodeRepository.observeMonths()`、`observeMonth(monthKey)`、`getMessageBody(messageId)`、`indexMessage(...)`、`rebuildAll()`、`reconcile()`、`deleteMessageIds(...)`、`deleteThreadIds(...)`。

- [ ] 建立索引实体、元数据实体和 DAO；索引包含 message/thread/address/timestamp/month/code/signature/ruleVersion/generation，不包含正文。
- [ ] DAO 只读取活动代际，月份和月内消息均倒序；完整重建在成功后事务切换代际。
- [ ] TelephonyDataSource 增加只读 SMS 摘要、按单个 ID 读取正文和按 ID/会话检查存在性的接口。
- [ ] 仓库使用现有 MessageParser 识别 `ParsedResult.VerificationCode`，实现增量校验、完整重建、即时写入和失效清理。
- [ ] 在 Koin 中注册数据库与仓库。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期通过。

### Task 2: 后台调度与短信生命周期接入

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/VerificationCodeIndexWorker.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/VerificationCodeIndexScheduler.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/receiver/SmsReceiver.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/PixelTextApplication.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Consumes: Task 1 repository刷新与清理接口。
- Produces: `scheduleReconcile()`、`scheduleFullRebuild()`，使用唯一 WorkManager 队列；新 SMS 即时索引；删除与规则更新同步刷新。

- [ ] Worker 通过输入模式执行 reconcile 或 rebuild，失败返回 retry，日志只输出英文元数据。
- [ ] App 启动调度增量校验，规则安装/回退后调度完整重建。
- [ ] SmsReceiver 插入成功后把 messageId/threadId/address/body/timestamp 交给仓库即时索引。
- [ ] 应用内删除消息或会话后同步清理索引。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期通过。

### Task 3: 验证码 ViewModel 与按月页面

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/VerificationCodeViewModel.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/VerificationCodeScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: 月份分页状态、`loadNextMonth()`、`refresh()`、`rebuildAll()`、`toggleMessageMode(messageId)`、LRU 正文缓存（30 条）、展开状态和一次性 Snackbar 事件。

- [ ] ViewModel 首次加载最新月份，接近底部时加载下一月份，并使用 SavedStateHandle 保存展开 ID。
- [ ] 原文模式仅按单条 messageId 查询；正文存入 30 条 LRU 内存缓存，缺失时删除索引并发出提示。
- [ ] 页面使用 LazyColumn stickyHeader 展示本地化月份，默认卡片只使用索引字段。
- [ ] 实现复制、显示原文/验证码、进入会话、下拉刷新、完整重建菜单、初始化/空/错误/刷新状态。
- [ ] 页面上报带阈值的滚动方向，不直接持有首页 FAB。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期通过。

### Task 4: 首页嵌套导航与共享 FAB

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/HomeScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt`

**Interfaces:**
- Consumes: 现有 ConversationListScreen 导航回调、Task 3 VerificationCodeScreen 回调。
- Produces: 首页内部 `conversations`/`verification_codes` NavHost、Material 3 NavigationBar、共享 ExtendedFloatingActionButton。

- [ ] 把外层起始目的地改为首页容器，底栏只存在于 HomeScreen。
- [ ] HomeScreen 内嵌 NavHost，默认会话；验证码页系统返回先切回会话。
- [ ] 从 ConversationListScreen 移除本地开始聊天 FAB，由 HomeScreen 共享原点击行为。
- [ ] 两个子页面上报滚动方向；切换页面时 FAB 恢复展开，展开/收起使用 Material 动画。
- [ ] 保持详情、搜索、设置、归档、骚扰和外部 deep link 行为不变。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期通过。

### Task 5: 整体验证与审查

**Files:**
- Verify: `app/src/main/`
- Verify: `docs/plans/2026-07-16-verification-code-view-design.md`

- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期 `BUILD SUCCESSFUL`。
- [ ] 运行 `./gradlew :app:lintDebug`，记录既有基线问题并确保无新增错误。
- [ ] 搜索确认 MMS 不进入验证码解析和索引。
- [ ] 检查索引实体不含正文、底栏不出现在二级页面、删除与规则更新触发正确。
- [ ] 完成独立代码审查，修复全部 Critical 和 Important 问题。
