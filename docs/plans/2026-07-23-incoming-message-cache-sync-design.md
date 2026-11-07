# 新短信会话同步设计

## 原问题与保留目标

2026-07 的故障是短信已写入 Telephony 并发出通知，首页仍展示旧会话缓存；当时搜索直接查 Provider，因此能找到首页尚未出现的短信。现有搜索已经改查本地镜像，不再具有这一旧数据源差异。

原修复解决 Receiver 冷启动漏观察、首页一次性读旧缓存和 LazyColumn 稳定 key 保留旧首项锚点的问题。后来消息镜像与加载性能方案替代了独立 `cached_conversation` 数据路径，但仍要求消息同步后首页响应更新、浏览列表中部时不强制跳顶。

## 当前数据流

1. `SmsReceiver` 写入系统 SMS 后取得消息 ID 和会话 ID，在一次 `goAsync()` 生命周期中分别处理镜像唤醒和验证码索引，失败相互隔离。
2. 有确切消息 ID 时，`ConversationCacheRepository.syncIncomingSms(messageId)` 持久标记对应镜像消息 dirty，并调用 `MessageMirrorScheduler.enqueueMetadata()`。只有无法获得消息 ID 时才回退 `syncThreads()`；该兼容接口也只排队，不在 Receiver 内扫描整库。
3. `MirrorChangeObserver` 负责系统变化观察；`MessageMirrorSynchronizer` 与增量同步器把消息写入主库。会话是镜像消息的查询投影，不再维护独立 `cached_conversation` 事实表。
4. `MessageRepositoryImpl.getAllConversations()` 首次发布前要求增量同步完成，随后结合镜像变化、设置和联系人缓存变化，执行主库会话查询。
5. `ConversationListViewModel` 持续收集会话流，异常按 1–30 秒退避重建订阅。首页不自行注册第二套 Telephony ContentObserver。
6. `ConversationListScreen` 仍记录旧首项并等待一帧布局；只有原首项被下移且仍为滚动锚点时滚动到 0，浏览中部时保持位置。

## 刷新与并发

当前下拉刷新及轻量刷新均调用 `MessageRepository.refreshConversations()`，推动增量同步，由现有订阅发布结果；不再先 `forceSyncConversations()` 全量扫描后 `first()` 读取。完整修复仍可通过 `forceSyncConversations()`／`ConversationCacheRepository.fullSync()` 执行对账与附件校验，不是每次下拉或 ON_RESUME 的默认动作。

镜像同步共用 `MirrorSynchronizationLock` 等当前同步边界，不能恢复旧独立会话缓存 Mutex 和 DAO。详细结构见[消息镜像设计](2026-09-06-message-mirror-design.md)和[消息加载性能方案](2026-11-05-message-loading-performance.md)。镜像已覆盖 SMS/MMS，不能将“当时仅修复 SMS”理解为当前 MMS 没有同步链路。

## 错误与隐私

- Provider 写入失败、同步排队失败和验证码索引失败分别处理，不让其中一个失败吞掉其余流程。
- 后台持久任务负责后续同步，Receiver 不等待历史全量扫描。
- 刷新失败保留现有可用列表，结束刷新提示；首次尚无数据时允许显示可恢复错误。
- 日志使用英文元数据，不记录短信正文。

## 历史验证与当前边界

[原实施记录](2026-07-23-incoming-message-cache-sync.md)保留了当时编译、Lint、模拟器冷进程／后台收信、全量下拉修复和列表锚点验证通过记录。它们证明当时实现，不能自动覆盖后续镜像重构。

本次只审计文档和源码。后续相关变更仍需按实际范围确认冷进程收信、前台订阅、增量刷新及列表中部位置保护，不新增或运行单元测试。
