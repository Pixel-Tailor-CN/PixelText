# 新短信会话缓存同步实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新短信到达后，无论应用进程是否存活，首页会话列表都能从 Room 缓存自动显示该消息，手动刷新能够执行 Telephony 对账。

**Architecture:** Receiver 在成功插入短信后按 `threadId` 增量同步缓存；Room DAO 提供响应式会话流，由 Repository 和 ViewModel 持续收集；下拉刷新执行显式全量同步。UI 不再直接监听 Telephony，避免读取旧缓存的竞态。

**Tech Stack:** Kotlin、Jetpack Compose、Coroutines Flow、Room、Android Telephony

## Global Constraints

- 最低 SDK 31，JVM target 21。
- 不新增 XML layout。
- 不新增或运行单元测试、仪器测试及测试依赖。
- 日志使用英文、小写开头、不使用句末标点。
- 避免无关格式化和重构。
- 不自动提交 Git，等待用户明确要求。

---

### Task 1: Receiver 增量同步缓存

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/receiver/SmsReceiver.kt`

**Interfaces:**
- Consumes: `ConversationCacheRepository.syncThreads(threadIds: List<Long>)`
- Produces: 短信插入完成后的缓存更新

- [x] **Step 1: 注入 ConversationCacheRepository**

在 `SmsReceiver` 中增加：

```kotlin
private val conversationCacheRepository: ConversationCacheRepository by inject()
```

- [x] **Step 2: 合并 Receiver 后台任务**

在获得 `messageId` 和 `threadId` 后，仅调用一次 `goAsync()`。IO 协程先执行：

```kotlin
conversationCacheRepository.syncThreads(listOf(threadId))
```

然后在 `messageId != null` 时执行原有验证码索引，并在 `finally` 中调用 `pendingResult.finish()`。

- [x] **Step 3: 保持失败隔离**

缓存同步和验证码索引分别使用 `runCatching` 或独立 `try/catch`，确保其中一个失败不会跳过另一个；缓存失败日志使用：

```kotlin
Log.e(TAG, "conversation cache sync failed thread_id=$threadId", error)
```

- [x] **Step 4: 编译检查**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`

### Task 2: Room 会话流与首页持续订阅

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/db/ConversationCacheDatabase.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/ConversationCacheRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationListViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt`

**Interfaces:**
- Produces: `CachedConversationDao.observeAllConversations(): Flow<List<CachedConversationEntity>>`
- Produces: `ConversationCacheRepository.observeAllConversations(): Flow<List<ConversationModel>>`
- Preserves: `MessageRepository.getAllConversations(): Flow<List<ConversationModel>>`

- [x] **Step 1: 增加 Room Flow 查询**

在 `CachedConversationDao` 中增加：

```kotlin
@Query("SELECT * FROM cached_conversation ORDER BY timestamp DESC")
fun observeAllConversations(): Flow<List<CachedConversationEntity>>
```

- [x] **Step 2: 暴露领域模型流**

在 `ConversationCacheRepository` 中增加：

```kotlin
fun observeAllConversations(): Flow<List<ConversationModel>> =
    dao.observeAllConversations().map { entities ->
        entities.map { it.toConversationModel() }
    }
```

使用 `AtomicBoolean` 保护 `startObserving()`，使重复调用不再重复注册 ContentObserver；`stopObserving()` 仅在成功注册后注销。

- [x] **Step 3: Repository 持续映射列表**

`MessageRepositoryImpl.getAllConversations()` 先确保缓存已初始化，然后 `emitAll` 缓存 Flow。每次缓存变化时重新读取归档集合和隐藏骚扰集合，过滤并补充联系人名称。

- [x] **Step 4: 调整显式刷新**

`ConversationListViewModel` 的常驻加载继续 `collect` 会话 Flow。`refreshSilent()` 使用 `first()` 读取当前缓存快照；手动 `refreshConversations()` 先调用 `forceSyncConversations()`，再用 `first()` 更新页面并结束刷新状态。

- [x] **Step 5: 移除 UI Telephony ContentObserver**

删除 `ConversationListScreen` 中直接注册 `Telephony.Sms` 和 `Telephony.Mms` 的 `DisposableEffect`，并清理只由它使用的 `ContentObserver`、`Handler`、`Looper` import。

- [x] **Step 6: 编译检查**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`

### Task 3: 回归验证

**Files:**
- Verify only

**Interfaces:**
- Consumes: Debug APK 与 Android 模拟器
- Produces: 新短信可见性验证结果

- [x] **Step 1: 运行 Lint**

Run:

```powershell
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [x] **Step 2: 安装 Debug APK**

Run:

```powershell
.\gradlew.bat :app:installDebug --no-daemon --console=plain
```

Expected: 安装到当前模拟器成功。

- [x] **Step 3: 验证冷进程短信接收**

让应用退到后台并结束进程，通过模拟器发送唯一内容的 SMS。直接启动应用，确认首页无需点击通知即可出现对应号码与内容。

- [x] **Step 4: 验证前台订阅**

保持应用进程存活并切到其他应用，再发送另一条唯一 SMS。返回 Pixel Text，确认首页通过 Room Flow 自动显示新内容。

- [x] **Step 5: 验证下拉对账**

制造一次缓存落后于 Telephony 的状态后执行下拉刷新，确认全量同步结束后会话出现且刷新指示器正常关闭。

- [x] **Step 6: 清理测试短信并检查工作区**

删除本次模拟器中创建的唯一测试会话，运行：

```powershell
git diff --check
git status --short
```

Expected: 无空白错误，仅显示本任务相关文件。

### Task 4: 新置顶会话的列表锚点

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt`

**Interfaces:**
- Consumes: `ConversationListUiState.Success.conversations`
- Consumes: `LazyListState.firstVisibleItemIndex`
- Produces: 用户原本位于顶部时，新首项自动进入视口

- [x] **Step 1: 记录上一版首会话**

在 `listState` 附近保存上一版列表首个 `threadId`。仅以首项 ID 变化作为检查触发条件。

- [x] **Step 2: 布局后判断滚动锚点**

首项变化时先等待一帧布局，再查找旧首项在新列表中的索引。仅当旧首项索引大于 0，并且 `listState.firstVisibleItemIndex` 等于该索引时，调用：

```kotlin
listState.scrollToItem(0)
```

这表示 LazyColumn 正在保持更新前的首项；若用户原本浏览列表中部，两者不会相等，不得自动滚动。

- [x] **Step 3: 验证两种滚动状态**

应用位于列表顶部时切换到后台并接收新短信，返回后新首项应直接可见。应用位于列表中部时重复操作，返回后应保持原浏览位置。

- [x] **Step 4: 编译检查**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`
