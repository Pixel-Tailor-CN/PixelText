# 新短信会话缓存同步设计

## 问题

新短信已经写入系统 Telephony 短信库并生成通知，但首页会话列表可能继续展示旧的 Room 缓存。搜索页直接查询 Telephony，因此“未读”筛选仍能找到该短信；点击通知进入会话后，标记已读流程会显式同步对应会话，首页随后恢复。

当前链路存在两个缺口：

1. 应用进程被系统回收后，短信 Receiver 拉起的是新进程。会话缓存的 ContentObserver 只有首页加载后才注册，因此会错过已经发生的短信插入事件。
2. 应用进程存活时，首页 ContentObserver 与缓存 ContentObserver 同时响应 Telephony 变化。首页可能先读取旧缓存，缓存稍后才同步完成，而首页并未持续观察 Room 的后续变化。

模拟器验证还确认了第三个 UI 层缺口：

3. `LazyColumn` 使用稳定的 `threadId` 作为 key。用户原本位于列表顶部时，新会话插入或旧会话移动到第一位，Compose 会保持原首项作为滚动锚点，使新首项位于当前视口上方。此时缓存和页面状态都已包含新会话，但用户仍需要向上滚动才能看到。

## 方案

采用四层修复：

1. `SmsReceiver` 在短信成功写入并获得有效 `threadId` 后，显式调用会话缓存的 `syncThreads`。同步与验证码索引共用一次 `goAsync` 生命周期，避免 Receiver 结束后后台任务被提前终止。
2. `CachedConversationDao` 暴露按时间倒序的 `Flow` 查询，`MessageRepository` 将首页会话数据改为持续订阅 Room。缓存完成增量更新后，首页自动收到新列表，不再依赖 UI 层抢先触发的一次性读取。
3. 手动下拉刷新先执行 `forceSyncConversations`，再读取最新缓存。生命周期恢复和业务操作保留轻量缓存读取，不在每次回到前台时执行全量扫描。
4. 首页记录上一版列表的首个 `threadId`。新首项出现后等待 LazyColumn 完成一帧布局；如果当前可见首项正是被下移的旧首项，说明用户更新前位于顶部，此时滚动到索引 0。用户原本浏览列表中部时不改变其位置。

同时移除首页直接监听 Telephony 的 ContentObserver，避免它与缓存同步观察器并发执行。`ConversationCacheRepository.startObserving` 增加幂等保护，防止首页多次加载造成重复注册。

所有全量和增量缓存同步共用同一个 Mutex，锁住从 Telephony 快照读取到 Room 写入的完整过程，避免旧的全量快照覆盖刚完成的新短信增量同步。首页常驻 Flow 异常时按 1 秒到 30 秒的退避间隔自动重建；Receiver 中缓存同步与验证码索引并行执行，避免长会话缓存扫描串行阻塞验证码索引。

## 数据流

```text
SMS_DELIVER
  -> 写入 Telephony
  -> 获取 threadId
  -> 增量同步 cached_conversation
  -> Room Flow 发出新列表
  -> ConversationListViewModel 更新首页
  -> 若旧首项仍是当前滚动锚点，则滚动到新首项
```

手动刷新：

```text
下拉刷新
  -> Telephony 全量对账
  -> 更新 cached_conversation
  -> Room Flow 发出新列表
  -> 结束刷新状态
```

## 错误处理

- Telephony 写入失败时保持现有错误日志和通知行为。
- 缓存增量同步失败时记录英文日志，不阻止通知、垃圾短信检测和验证码索引。
- 手动全量同步失败时结束刷新状态并保留当前可用列表。
- 日志沿用项目约定：英文、小写开头、不使用句末标点。

## 验证

项目约定不新增或运行单元测试，因此使用以下验证：

- `:app:compileDebugKotlin`
- `:app:lintDebug`
- 模拟器复现：结束应用进程后接收短信，直接打开首页即可看到；应用在后台时接收短信，首页恢复后可自动看到；下拉刷新能从 Telephony 恢复人为制造的旧缓存。
- 验证列表中部保护：滚动到列表中部后接收短信，页面不得自动跳回顶部。

## 非目标

- 不调整通知样式和点击行为。
- 不修改短信解析、垃圾短信识别或验证码提取逻辑。
- 不为每次 Activity `ON_RESUME` 执行全量 Telephony 扫描。
- 本次仅修复 SMS；MMS 接收缓存同步可在验证相同现象后复用该链路。
