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

- [ ] 按设计的存储表定义 Entity，消息源标识唯一索引；子表以 local_id 外键级联，清理队列不随父消息被删除。下载请求表是应用操作状态，镜像扫描不能覆盖它。
- [ ] MirrorMessageModel 包含完整源键、原始箱状态、可空 thread、全部参与方与全部 part/附件；现有 MessageModel 只是兼容投影。
- [ ] MirrorSyncState 分别包含初始化阶段、扫描完成集合、未完整子结构数量、pending 下载数量、附件失败数量、最后成功时间。不能只返回 isReady。
- [ ] 定义可逆原始值编码：列名、类型、null 与值；Long 保持 Long 精度，BLOB 可逆。结构列与原始快照同时保存。
- [ ] 建立时间、thread、transport/source_id、subscription_id、参与方号码、复制状态索引；不要声称普通 B-tree 能加速任意中间号码子串。
- [ ] 数据库开启 schema 导出并把迁移文件放入 `app/schemas/`，所有升级显式迁移，不使用 destructiveMigration。
- [ ] 为“单消息结构替换”“批次与检查点提交”“删除与清理任务入队”分别实现 DAO 事务，不在事务内读取 Provider 或复制文件。

## 任务 2：无信息压缩的源读取器

**文件：** 新增 `data/source/mirror/TelephonyMirrorSource.kt`、`ProviderRowSnapshot.kt`。

**接口：** `readSmsPage(afterId: Long?, limit: Int)`、`readMmsPage(afterId: Long?, limit: Int)`、`readMessage(key: SourceMessageKey)`、`readMmsChildren(sourceId: Long)`、`readThreadSources()`，结果明确区分成功空集合与失败。方法均可取消。

- [ ] 根集合读取所有状态，独立扫描 SMS/MMS，不先枚举 thread。批次采用稳定 source_id 键集、初始 200 条。
- [ ] 读取实际列集合与类型，null Cursor 抛出或返回失败；可选字段缺失记录能力覆盖，禁止填假默认值伪装源值。
- [ ] 从同一源行生成强类型字段和原始快照；保留箱类型而非仅 isReceived；转换时间时保留原始单位。
- [ ] MMS 查询全部 addr 与 part，按 seq/source_id 稳定排序，所有 MIME 都保留，文本/SMIL 编码不丢弃。不能调用旧 getMmsTextContent() 或 getMmsAddress() 当完整源。
- [ ] 会话/canonical 数据读取独立报告能力结果，失败不阻止无 thread 消息入库，也不能静默报告完整。
- [ ] ContentResolver 查询接 CancellationSignal；每批与流处理检查取消；查询失败不能返回 emptyList 掩盖错误。
- [ ] 建立无内容日志的覆盖报告：源集合、列覆盖、记录/part/附件数量、错误分类。

## 任务 3：附件私有存储与回收

**文件：** 新增 `data/source/mirror/MirrorAttachmentStore.kt`、`data/repository/mirror/MirrorAttachmentCopier.kt`；核对 `app/src/main/res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`。

**接口：** `suspend fun copyPart(localId: Long, revision: Long, partId: Long)`；`suspend fun drainCleanupQueue()`；输入 URI 仅从受控源 part 映射获得。

- [ ] 使用 noBackupFilesDir/message-mirror，生成随机安全文件名；原始附件名仅元数据，禁止直接拼为路径。
- [ ] 用有界缓冲流完整复制源文件，计算字节数/SHA-256，临时文件完成后原子改名。不能把整个视频读入内存。
- [ ] READY 提交前检查 local_id/revision/part 仍有效；不匹配时只清理本次临时产物，不修改新消息。
- [ ] 已完成附件只在源变化或完整校验需要时重新读源；校验摘要不一致生成新文件并替换，旧文件入队清理。
- [ ] PENDING_DOWNLOAD 不排网络任务；权限/读流错误记 SOURCE_UNREADABLE 或 COPY_FAILED；无空间时退避并显示附件未完成。
- [ ] 实现删除文件任务幂等重试、私有根路径验证、引用再确认；启动修复绑定文件缺失、未绑定已改名文件和中断的 COPYING。
- [ ] 新镜像数据库、WAL/SHM 与附件排除云备份/设备迁移，保留其他已有配置。

## 任务 4：首次同步、更新与可靠删除

**文件：** 新增 `data/repository/mirror/MessageMirrorSynchronizer.kt`、`MirrorChangeObserver.kt`。

**接口：** `suspend fun reconcile()`、`suspend fun markDirty(key: SourceMessageKey?)`，null 表示集合失效；`suspend fun copyPendingAttachments()`。共享串行同步锁，事务通过任务 1 DAO 完成。

- [ ] 先注册观察、持久化 dirty，再扫描根源集合。每批提交记录和检查点；重启恢复不丢任务，不复用中断扫描做删除。
- [ ] MMS 子结构成功读取才原子替换，失败保留既有结构并设置不完整；附件复制独立排队。
- [ ] 完成基础扫描后排空 dirty 队列、对账并更新各集合成功状态，扫描竞争时继续排队；不能在还有失败子结构时显示完整。
- [ ] 定向 URI 解析支持 sms、mms 与可解析的 part；无法解析就排集合对账，不将 URI 末尾所有 ID 当 threadId。
- [ ] 完整对账覆盖历史记录字段与 part，包括同 ID 内容更新；不是只扫描 newest ID。逐批处理，不长时间占用主线程。
- [ ] 缺失候选逐批做不带业务筛选的存在性复查；仅明确成功空结果可删除。null、失败、取消、权限变化均跳过删除并保留恢复信息。
- [ ] 删除事务移除消息及子结构并将路径入队；协调关联的本地派生索引清理，先事务提交后删文件。
- [ ] 通过 revision 与串行提交阻止陈旧扫描或附件任务复活已删消息；同 source ID 重现时按本地删除事实建立新记录。
- [ ] 记录全量/定向同步成功时间、错误和处理计数，不记录正文与号码。
