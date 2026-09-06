# 完整消息镜像实施计划

> 执行者先阅读 [镜像设计](2026-09-06-message-mirror-design.md)，使用 executing-plans 逐项执行；本计划是检索页计划的前置依赖。复选框只在取得相应验证结果后勾选。

**目标：** 完整保存可读取 SMS/MMS、关联元数据及已下载附件，可靠同步新增/变更/删除，让消息能独立于会话读取。

**架构：** 新建 Room 镜像库、原始 Provider 读取器、串行同步协调器与私有附件存储。保留系统写入路径，核心列表/详情读取切换到本地兼容投影。

**技术栈：** Kotlin、Room、Flow、WorkManager、ContentResolver、应用私有文件存储，复用现有依赖与权限。

## 全局约束

- 全部 SMS/MMS 箱类型；完整参与方与 part；归档/骚扰不限制入库。
- 仅复制已下载内容，镜像代码无下载调用；确认删除后级联删除本地数据并清理附件。
- 新彩信自动下载设置默认关闭，用户可开启；手动下载与镜像复制分离。
- 中文注释和文档；英文日志只含阶段/计数/错误，不打印个人内容。
- 不新增单元测试或测试依赖；编译、Lint、真机验证。
- 不修改/提交用户现有 Gradle 版本调整，不重建或清空其他业务数据库。
- 下列新类型位于 `app/src/main/java/vip/mystery0/pixel/text/`，文件路径均相对此包，资源路径另列。

## 任务 1：源模型与镜像数据库

**文件：** 新增 `domain/model/mirror/SourceMessageKey.kt`、`MirrorMessageModel.kt`、`MirrorSyncState.kt`；新增 `data/db/mirror/MessageMirrorDatabase.kt`、`MirrorEntities.kt`、`MirrorDao.kt`、`MirrorSyncDao.kt`。

核心标识和接口：

```kotlin
enum class MessageTransport { SMS, MMS }
data class SourceMessageKey(val transport: MessageTransport, val sourceId: Long)

interface MessageMirrorRepository {
    fun observeSyncState(): Flow<MirrorSyncState>
    suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel?
    fun observeMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageModel>>
}
```

- [x] 按设计的存储表定义 Entity，消息源标识唯一索引；子表以 local_id 外键级联，清理队列不随父消息被删除。下载请求表是应用操作状态，镜像扫描不能覆盖它。
- [x] MirrorMessageModel 包含完整源键、原始箱状态、可空 thread、全部参与方与全部 part/附件；现有 MessageModel 只是兼容投影。
- [x] MirrorSyncState 分别包含初始化阶段、扫描完成集合、未完整子结构数量、pending 下载数量、附件失败数量、最后成功时间。不能只返回 isReady。
- [x] 定义可逆原始值编码：列名、类型、null 与值；Long 保持 Long 精度，BLOB 可逆。结构列与原始快照同时保存。
- [x] 建立时间、thread、transport/source_id、subscription_id、参与方号码、复制状态索引；不要声称普通 B-tree 能加速任意中间号码子串。
- [x] 数据库开启 schema 导出并把迁移文件放入 `app/schemas/`，所有升级显式迁移，不使用 destructiveMigration。
- [x] 为“单消息结构替换”“批次与检查点提交”“删除与清理任务入队”分别实现 DAO 事务，不在事务内读取 Provider 或复制文件。

## 任务 2：无信息压缩的源读取器

**文件：** 新增 `data/source/mirror/TelephonyMirrorSource.kt`、`ProviderRowSnapshot.kt`。

**接口：** `readSmsPage(afterId: Long?, limit: Int)`、`readMmsPage(afterId: Long?, limit: Int)`、`readMessage(key: SourceMessageKey)`、`readMmsChildren(sourceId: Long)`、`readThreadSources()`，结果明确区分成功空集合与失败。方法均可取消。

- [x] 根集合读取所有状态，独立扫描 SMS/MMS，不先枚举 thread。批次采用稳定 source_id 键集、初始 200 条。
- [x] 读取实际列集合与类型，null Cursor 抛出或返回失败；可选字段缺失记录能力覆盖，禁止填假默认值伪装源值。
- [x] 从同一源行生成强类型字段和原始快照；保留箱类型而非仅 isReceived；转换时间时保留原始单位。
- [x] MMS 查询全部 addr 与 part，按 seq/source_id 稳定排序，所有 MIME 都保留，文本/SMIL 编码不丢弃。不能调用旧 getMmsTextContent() 或 getMmsAddress() 当完整源。
- [x] 会话/canonical 数据读取独立报告能力结果，失败不阻止无 thread 消息入库，也不能静默报告完整。
- [x] ContentResolver 查询接 CancellationSignal；每批与流处理检查取消；查询失败不能返回 emptyList 掩盖错误。
- [x] 建立无内容日志的覆盖报告：源集合、列覆盖、记录/part/附件数量、错误分类。

## 任务 3：附件私有存储与回收

**文件：** 新增 `data/source/mirror/MirrorAttachmentStore.kt`、`data/repository/mirror/MirrorAttachmentCopier.kt`；核对 `app/src/main/res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`。

**接口：** `suspend fun copyPart(localId: Long, revision: Long, partId: Long)`；`suspend fun drainCleanupQueue()`；输入 URI 仅从受控源 part 映射获得。

- [x] 使用 noBackupFilesDir/message-mirror，生成随机安全文件名；原始附件名仅元数据，禁止直接拼为路径。
- [x] 用有界缓冲流完整复制源文件，计算字节数/SHA-256，临时文件完成后原子改名。不能把整个视频读入内存。
- [x] READY 提交前检查 local_id/revision/part 仍有效；不匹配时只清理本次临时产物，不修改新消息。
- [x] 已完成附件只在源变化或完整校验需要时重新读源；校验摘要不一致生成新文件并替换，旧文件入队清理。
- [x] PENDING_DOWNLOAD 不排网络任务；权限/读流错误记 SOURCE_UNREADABLE 或 COPY_FAILED；无空间时退避并显示附件未完成。
- [x] 实现删除文件任务幂等重试、私有根路径验证、引用再确认；启动修复绑定文件缺失、未绑定已改名文件和中断的 COPYING。
- [x] 新镜像数据库、WAL/SHM 与附件排除云备份/设备迁移，保留其他已有配置。

## 任务 4：首次同步、更新与可靠删除

**文件：** 新增 `data/repository/mirror/MessageMirrorSynchronizer.kt`、`MirrorChangeObserver.kt`。

**接口：** `suspend fun reconcile()`、`suspend fun markDirty(key: SourceMessageKey?)`，null 表示集合失效；`suspend fun copyPendingAttachments()`。共享串行同步锁，事务通过任务 1 DAO 完成。

- [x] 先注册观察、持久化 dirty，再扫描根源集合。每批提交记录和检查点；重启恢复不丢任务，不复用中断扫描做删除。
- [x] MMS 子结构成功读取才原子替换，失败保留既有结构并设置不完整；附件复制独立排队。
- [x] 完成基础扫描后排空 dirty 队列、对账并更新各集合成功状态，扫描竞争时继续排队；不能在还有失败子结构时显示完整。
- [x] 定向 URI 解析支持 sms、mms 与可解析的 part；无法解析就排集合对账，不将 URI 末尾所有 ID 当 threadId。
- [x] 完整对账覆盖历史记录字段与 part，包括同 ID 内容更新；不是只扫描 newest ID。逐批处理，不长时间占用主线程。
- [x] 缺失候选逐批做不带业务筛选的存在性复查；仅明确成功空结果可删除。null、失败、取消、权限变化均跳过删除并保留恢复信息。
- [x] 删除事务移除消息及子结构并将路径入队；协调关联的本地派生索引清理，先事务提交后删文件。
- [x] 通过 revision 与串行提交阻止陈旧扫描或附件任务复活已删消息；同 source ID 重现时按本地删除事实建立新记录。
- [x] 记录全量/定向同步成功时间、错误和处理计数，不记录正文与号码。

## 任务 5：生命周期、系统操作与后台续跑

**文件：** 新增 `worker/MessageMirrorWorker.kt`、`MessageMirrorScheduler.kt`；修改 `PixelTextApp.kt`、`di/AppModule.kt`、必要的前台权限/生命周期入口；接入 `receiver/SmsReceiver.kt`、`MmsReceiver.kt`、`NotificationActionReceiver.kt`、`mms/MmsDownloadReceiver.kt`、`service/HeadlessSmsSendService.kt`、`data/repository/MessageRepositoryImpl.kt`。

- [x] 在 Koin 注册 database/source/store/synchronizer/repository/scheduler，保证进程内共享同步协调器。
- [x] 使用唯一 WorkManager 任务协调续跑，初始每轮最多工作约 2 分钟后提交检查点并安排后续；不能为大附件依赖无限运行的 Receiver 协程。
- [x] 首次可读、进入前台、权限恢复、手动刷新触发对账；默认安排每天一次低频完整对账，受系统调度约束，无网络条件。任务回读当前权限，失败等待恢复，不自动弹新权限。
- [x] Observer 回调只标 dirty/调度；收到/发送/已读/删除/下载完成写系统成功后追加可解析的定向 dirty 或集合 dirty，Receiver 使用现有安全生命周期且不执行全库复制。
- [x] 重试保留数据与检查点，CancellationException 传播；权限缺失不热循环，空间不足使用可恢复错误状态。
- [x] 镜像 worker/source/store 没有 downloadMultimediaMessage 或 HTTP 调用；真实下载成功后仅回读已形成的 Provider part。

## 任务 5A：可配置的彩信下载与正确持久化

**文件：** 修改 `domain/settings/AppSettingsRepository.kt`、`data/repository/AppSettingsRepositoryImpl.kt`、`viewmodel/SettingsViewModel.kt`、`ui/screen/SettingsScreen.kt`、`receiver/MmsReceiver.kt`、`mms/MmsDownloadReceiver.kt`；新增 `mms/MmsDownloadCoordinator.kt`、`RetrieveConfParser.kt`、`MmsProviderWriter.kt`、`ui/message/cards/MmsDownloadCard.kt`；按需扩展现有 FileProvider 或添加仅暴露临时 PDU 目录的 provider 配置。

**接口：** 设置 `autoDownloadMms: Boolean = false` 和 `setAutoDownloadMms(enabled: Boolean)`；下载入口 `suspend fun requestDownload(key: SourceMessageKey, userInitiated: Boolean)`。

- [x] 沿用现有设置存储实现保存开关，默认 false，升级默认关闭；设置页用 SwitchPreference，说明可能使用移动数据。
- [x] 移除接收器无条件 triggerMmsDownload；保存 notification 占位、FROM 地址与头字段。保留原 NotificationInd 类型，初始为待下载，只有真正派发请求才显示下载中。
- [x] 接收新消息读取一次开关并按条件调用下载协调器；手动下载不依赖开关。启用开关不遍历历史占位。
- [x] 协调器复核消息仍存在、未过期、有效 content location 和接收 subId；持久令牌幂等去重，不因手动重复点击并发下载。
- [x] 给系统提供受控临时 PDU URI，精确授权写入，回调使用令牌定位请求；不把下载回调成功等同于 part 已落库。不得使用整个私有文件目录的宽泛 FileProvider 路径。
- [x] 为 RetrieveConf 实现独立解析：头字段/字符集、multipart 边界、所有地址和 part；保留未知字段和未识别 MIME。现有通知解析器不得作为完整解析器使用。采用公开可审查实现时保留许可证并纳入代码评审，不调用隐藏 API 反射。
- [x] Provider writer 关联已有占位，写入头/地址/part/字节流，成功后更新完成状态。持久记录写入阶段，处理中断能继续或清理本请求半成品；不删除不属于本请求的消息。
- [x] 回调或恢复先检查源记录是否已被删除；删除后不复活、不再写文件。任务结束撤销临时授权；原始 PDU 保留供保真和恢复，源消息删除后清理。
- [x] 卡片提供下载、下载中、失败重试和过期/无 SIM 等反馈；系统真正保存 part 后通知同步，附件复制仍走任务 3。
- [ ] 真机验证默认关闭、开启后新消息自动下载、关闭后待下载、手动下载、重复回调、SIM 失效与 PDU 解析失败；抓取调用计数确认镜像不发下载请求。

## 任务 6：本地读取接口与现有页面适配

**文件：** 新增 `domain/repository/MessageMirrorRepository.kt`、`data/repository/mirror/MessageMirrorRepositoryImpl.kt`、`MirrorMessageMapper.kt`、`viewmodel/MirrorMessageDetailViewModel.kt`、`ui/screen/MirrorMessageDetailScreen.kt`；修改 `data/repository/MessageRepositoryImpl.kt`、`ConversationCacheRepository.kt`、`ui/AppNavigation.kt`、必要的详情状态与 UI。

- [x] 实现任务 1 接口，新增 `observeConversationSummaries(): Flow<List<ConversationModel>>`，从本地消息聚合，业务归档与骚扰条件在读取侧应用。
- [x] getMessagesByThread/getMessages 与普通/归档/骚扰会话读取核心路径切到镜像，继续显示名/服务号 enrichment；不让 MessageRepository 的详情查询回落到旧 Provider 简化 row。
- [x] 停止旧摘要同步器独立扫描 Provider，保留其兼容方法作为新同步/投影的委托；旧库中的服务号表继续保留。
- [x] 保留发送、删除、已读系统写操作；成功后持久标记失效并更新 Flow，不对镜像做猜测式最终写入。
- [x] MirrorMessageMapper 明确箱状态/方向映射并保留完整模型，不把草稿、失败状态丢弃。现有图片卡取 READY 的本地图片路径，其他附件保存在完整模型。
- [x] 首次导入显示同步状态，后续同步显示旧数据与更新状态；附件未完成和无消息分开。无 thread 的消息支持按 SourceMessageKey 获取独立详情，不伪造 thread。
- [x] 独立详情路由使用 transport 与 sourceId，ViewModel 通过 getMessage 读取原始状态、正文、全部参与方和附件元数据；页面只读展示并提供待下载彩信下载入口，不实现草稿编辑/发送。消息不存在时显示已删除状态。
- [x] 检查验证码/骚扰独立后台任务尚用 Provider 的范围并记入交付说明；不因此把核心详情重新切回 Provider。

## 任务 7：真机与故障验证

- [x] `./gradlew :app:compileDebugKotlin` 与 `./gradlew :app:lintDebug`；不运行 test 任务。
- [ ] 使用脱敏内容构建收件箱/已发/草稿/发件箱/失败/排队状态样本，比对 Provider 与镜像源 ID、原始字段、地址与 part 数量。
- [ ] 图片、视频、音频、SMIL、多文本 part、同大小附件更新核对摘要；缺下载内容不会触发网络。
- [ ] 同步中新增/删除/改已读/改草稿/下载完成、杀进程后恢复、取消与权限撤销不误删。
- [ ] 模拟复制后未绑定、绑定后未清旧文件、磁盘满、丢失副本等中断点，核对重试和回收。
- [ ] 系统删除消息后镜像与附件清理；已有同 ID 新消息不会被旧任务绑定或删除。
- [ ] 空源成功、null Cursor、源集合拒绝访问分别验证；后两者绝不可清空本地库。
- [ ] 确认核心列表/详情由本地消息生成，历史图片可从私有副本读取；记录所有未验证设备差异与能力缺口。
- [ ] git diff --check，只提交本任务文件；输出真实同步覆盖和附件完整性，不以编译通过替代数据验收。

> 执行记录见 [实施与验证报告](2026-09-06-message-mirror-progress.md)。任务 1–6 的勾选代表代码实施和静态检查；设备故障场景仍以任务 7 及报告中实际证据为准。
