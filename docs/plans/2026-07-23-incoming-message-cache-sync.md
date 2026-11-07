# 新短信会话同步实现与历史验证

## 当前实现

旧独立会话缓存已被消息镜像替代，当前链路如下。代码路径相对 `app/src/main/java/vip/mystery0/pixel/text/`。

| 原实施位置 | 当前实现 |
| --- | --- |
| Receiver 直接按 threadId 同步旧缓存 | `SmsReceiver` 优先调用 `ConversationCacheRepository.syncIncomingSms(messageId)`；持久标记 dirty 并排队元数据同步，不在 Receiver 扫描整库 |
| `CachedConversationDao` / `cached_conversation` | `MessageMirrorRepository` 和镜像会话查询投影；旧缓存不再是运行时数据源 |
| `ConversationCacheRepository.startObserving()` 的独立观察器 | 委托 `MirrorChangeObserver.start()`，与消息镜像共享系统变化观察 |
| `getAllConversations()` 读取旧缓存 Flow | 首次增量追平后，结合镜像变更、设置和联系人缓存变更执行主库查询 |
| 下拉先全量同步再 first() 读缓存 | `ConversationListViewModel.refreshLoaded()` 调用 `refreshConversations()` 增量同步，现有订阅发布结果 |
| 手动完整对账 | `forceSyncConversations()`／`fullSync()` 仍提供完整修复与附件校验，不是普通下拉默认路径 |

Receiver 镜像唤醒和验证码索引共用一次 `goAsync()` 并隔离错误；首页 Flow 仍按 1–30 秒退避重试。列表锚点保护仍由 `ConversationListScreen` 实现。当前行为见[设计](2026-07-23-incoming-message-cache-sync-design.md)，后续镜像和性能变化不能复用旧缓存代码模板。

## 原实施验证记录（2026-07-23）

原任务 1、2、4 的 Kotlin 编译步骤及任务 3 的 Lint、安装和模拟器检查已勾选完成。下面保留原任务 3、4 的具体记录，涉及“全量下拉”等表述描述当时版本，不是当前增量刷新要求。本次文档核对未重跑这些场景，不将历史结果扩大到后续镜像实现。

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
