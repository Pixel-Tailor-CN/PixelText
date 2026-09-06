# 完整消息镜像核心批次实施记录

日期：2026-09-06。范围：核心计划任务 1–4；系统写入、设置、接收器、页面和 WorkManager 接入由主任务完成。

## 已实现内容

- 独立 `message_mirror.db`，共 12 张表。消息使用 `(transport, sourceId)` 唯一键，消息删除后同源 ID 重现会取得新的自增本地 ID；子表外键级联，文件清理队列独立保存。
- SMS/MMS 根行保留所有实际返回列，按列名、Cursor 类型、可空值编码。整数为十进制字符串，BLOB 为 Base64，不经浮点数转换。查询需要的时间、thread、箱状态、SIM、参与方、part 字段同时结构化保存。
- 全部 SMS/MMS 箱类型独立键集分页；不以会话、归档、骚扰或日期过滤消息。Provider 查询使用 `CancellationSignal`，区分成功空结果和失败。使用标准 Bundle 请求分页，忽略分页参数的 Provider 仍受客户端 200 行上限约束。
- MMS 保存全部地址与 part，保留 MIME、顺序、字符集、文件名、CID、位置、原始文本和扩展列。子结构任一查询失败时保留原结构，更新不完整状态并持久化待回读标记。
- 附件位于 `noBackupFilesDir/message-mirror`，随机文件名、64 KiB 流式复制、SHA-256 与字节数、文件同步和同目录原子改名。绑定前重新校验消息本地 ID、revision 与 part；更换、删除文件先写清理队列。
- 复制任务独立于元数据扫描，不调用 MMS 下载。无流的内联文本/SMIL 由原始快照持久保存；源应有流却不可读时保留错误及退避状态。READY 文件按 24 小时完整校验周期重新检查源摘要，明确的 part 变更和手动全文校验可提前触发内容验证。
- 恢复中断 COPYING、缺失的已绑定文件、未绑定的临时及已改名文件；回收前确认私有根路径与数据库引用，失败保留任务重试。
- 单消息替换、批次与检查点提交、确认删除与文件回收任务入队均使用 Room 事务；事务中不读取 Provider、不复制文件。
- 同步与文件绑定共用串行锁；大附件流复制在锁外执行，锁内仅领取任务和复核绑定；活跃复制令牌保护临时与已改名文件，不被恢复/孤儿回收误删。dirty 使用独立事务与随机令牌去重确认，扫描期间新通知不会被旧确认移除。
- 全量扫描覆盖旧记录，缺失候选再通过无筛选精确 URI 复查；异常、null Cursor、取消、权限不足均不作为不存在。派生缓存清理失败时保留消息和待回读任务。
- 主动预算让出写入完整批次检查点；崩溃中断的导入续扫不用于删除，之后再开始独立完整轮次。`ROUND` 状态持久化本轮完成源集合与起始集合 dirty 令牌，预算续跑跳过本轮已成功集合，避免两个大源互相重新开始而无法完成附属集合。
- 会话/canonical 能力独立报告，不能读取时不影响根消息保存，也不把整体同步状态报告为完整。
- Room schema 导出配置为 `app/schemas/`。版本 1 不需要旧库迁移；后续升级必须显式添加 Migration，没有 destructiveMigration。

## 接入接口

构造顺序：

```kotlin
val database = MessageMirrorDatabase.create(context)
val source = TelephonyMirrorSource(context)
val store = MirrorAttachmentStore(context)
val copier = MirrorAttachmentCopier(database, store)
val synchronizer = MessageMirrorSynchronizer(database, source, copier)
val repository = MessageMirrorRepositoryImpl(database, store)
val observer = MirrorChangeObserver(context, synchronizer, applicationScope)
```

同步接口：

```kotlin
suspend fun reconcile(timeBudgetMillis: Long = 120_000): Boolean
suspend fun markDirty(key: SourceMessageKey?, verifyAttachments: Boolean = false)
suspend fun requestAttachmentVerification()
suspend fun copyPendingAttachments(timeBudgetMillis: Long = 120_000): Boolean
```

两个 Boolean 为 `true` 时表示仍需续跑。预算在批次事务之间、附件之间检查；单个 Provider 请求或大附件流可能超过预算，协程取消仍向上传播。附件失败持久保存退避时间，Worker 应采用退避，不能立即忙循环。

`MirrorChangeObserver.onDirty: (() -> Unit)?` 用于安排唯一后台任务，`start()` 注册观察并持久化集合失效信号。主任务应在首次扫描前启动观察器。

可解析的 MMS part 变更使用 `attachments:MMS:<源 ID>` 独立 dirty 键持久化，普通消息变化不会覆盖这项校验请求。同步器成功回读消息及完整子结构后，先执行 `MirrorDao.invalidateAttachmentContent()`，再按原令牌确认 dirty；失败或取消不提前确认请求。

失效事务提升消息 revision，并同步该消息所有附件的 revision。READY 与 COPYING 均转为 SOURCE_PRESENT、清空 verifiedAt，包含没有本地路径的内联 part；正在锁外读取旧内容的复制任务无法再通过版本校验绑定。旧文件引用保留到新内容成功绑定后回收。COPY_FAILED、SOURCE_UNREADABLE 等失败项保留状态、attempts 和 retryAfter，源变化提示不会无条件清除失败退避。

`requestAttachmentVerification()` 以本地 ID 键集每批 200 条为现有 MMS 写入上述持久请求，由 `ConversationCacheRepository.fullSync()` 的用户手动刷新调用。普通前台 `forceReconcile` 仅强制元数据对账，不在每次恢复时重新读取全库视频；缺少可解析通知的源变更继续由每日完整校验兜底。

`MessageMirrorSynchronizer.onMessageDeleted: (suspend (SourceMessageKey) -> Unit)?` 在源确认不存在之后、镜像删除事务之前调用，用于清理其他业务数据库中的派生索引；失败时不删除镜像并继续保留 dirty。

读取仓库接口：

```kotlin
fun observeSyncState(): Flow<MirrorSyncState>
suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel?
fun observeMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageModel>>
fun observeAllMessages(): Flow<List<MirrorMessageModel>>
fun observeConversationSummaries(): Flow<List<ConversationModel>>
```

`MirrorMessageModel.toMessageModel()` 是过渡投影：SMS 正 ID、MMS 负 ID，拼接所有文本 part；图片只使用 READY 私有副本；`mmsDownloadPending` 严格按原始 `m_type == 130` 产生。解析结果、SIM 显示名等现有业务派生字段由主仓库补充。完整源模型保留全部参与方、原始列和全部附件，兼容投影不替代完整模型。

`completedCollections` 使用大写：`SMS`、`MMS`、`THREADS`、`CANONICAL`，另有 `TARGETED` 定向状态和 `ROUND` 整轮进度。结构、待下载、附件失败、待复制数量分别暴露；元数据完成不代表所有附件 READY。

`drainCleanupQueue(timeBudgetMillis: Long = 10_000): Boolean` 按路径键集分批回收，单轮失败不阻塞后续文件，不在本轮重试失败任务；返回值和剩余清理数量纳入同步/复制续跑判据。大量删除不会因为清理查询的单批 200 项上限而遗漏后续唤醒。

`mms_download_request` 及 DAO 提供 token、源 MMS ID、临时路径、阶段、SIM、更新时间与错误的持久存储。镜像扫描不会覆盖此表；主任务当前独立下载日志不依赖它。

## 验证与尚需设备核验

- 已完成核心代码静态自查，修复 Room 对 `isMms`/`hasMms` 推断 getter 冲突，SQL 投影改为 `latestIsMms`/`containsMms`。
- 已落实独立评审发现的整体轮次饥饿、清理续跑遗漏、大附件占用元数据锁问题；恢复缺失文件只重排 READY 状态，不反复清空已失败附件的退避时间。元数据与附件使用独立 Worker 链，高频观察唤醒合并为运行项及至多一个等待项；附件退避与文件清理失败不占用元数据续跑。
- 附件原地字节更新修复已完成只读复核：READY/COPYING 的失效与 revision 提升在同一事务内完成，失败退避不变，独立 dirty 在成功回读、失效之后才按令牌确认。之前仅清 verifiedAt、忽略 COPYING 或过滤无路径 part 的并发缺口均已关闭。
- 据主任务最终 APK 模拟器验证：无有效 thread 的待下载 MMS 可镜像，并已从搜索进入独立详情完成 UI 验证；文本、SMIL、音频、视频和未知二进制共 5 个 part 全部保留。后三项均为各 21 字节的合成载荷，副本字节数与 SHA-256 比对通过；此项仅验证 MIME 无关的字节复制，不代表音视频解码验证或全部真机场景覆盖。
- 同大小原地内容变更已验证：将 part 3 的 21 字节载荷从 v1 改为 v2，保持 part 元数据不变；主列表手动下拉触发 fullSync 后，READY 副本摘要变为期望的 `eb196d24cc0ea033f87d6e0f40fdf326e896031424ed2cd685c724de0a7cb3ee`。
- 源删除与私有文件回收已验证：删除本次合成 MMS 1 后，镜像 MMS 与附件数量均为 0，`no_backup/message-mirror` 目录为空。
- 验证环境已恢复：所有本次合成测试数据已清理，原有 104 条 SMS 保留，shell 的 `WRITE_SMS` app-op 已恢复为 `ignore`。
- 编译、Lint 与模拟器验证由主任务统一执行，最终结果补入主任务实施记录；本子任务没有新增或运行单元测试。
- 已只读核对备份规则：云备份、设备迁移与兼容备份均显式排除镜像数据库、WAL、SHM，保留原有排除项；附件自身位于 noBackup 目录。
- 未获得真机故障注入证据：大附件取消、磁盘满、权限切换、Provider null Cursor、系统库重建、复制与源变更竞争、厂商扩展列覆盖均仍需设备核验。
- Provider 不提供跨表一致性快照；本实现通过观察、dirty、完整对账与二次存在性复查持续收敛，不声称某个瞬间的绝对系统快照。普通号码索引仅用于适合的等值或前缀访问，不声称加速任意中间子串。
