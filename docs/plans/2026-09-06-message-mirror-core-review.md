# 消息镜像核心实现评审

审查范围：2026-09-06 当前未提交的镜像数据库、Provider 读取器、同步器、附件复制与回收，以及直接调用这些入口的 Worker 调度。以下均为静态路径审查发现，未运行单元测试、未修改实现代码。

## P1：附件退避期间持续追加即时完整扫描任务

- 位置：`app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MessageMirrorSynchronizer.kt:75-78`；关联 `worker/MessageMirrorWorker.kt:26-29`、`worker/MessageMirrorScheduler.kt:15-19`。
- 触发：一个附件因磁盘满或源不可读进入 `COPY_FAILED` / `SOURCE_UNREADABLE`，其 `retryAfter` 尚未到达。`pendingAttachments(now)` 返回空，但 `unfinishedAttachmentCount()` 仍大于零，所以 Worker 又无延迟追加下一项任务，并返回成功。每个新任务先重新完整扫描 SMS/MMS。`setBackoffCriteria` 只配置重试，无法约束这条成功后追加的路径，因此最长六小时的附件退避会变成六小时连续数据库/Provider 工作。
- 另一个放大路径：`MirrorAttachmentCopier.kt:89-94` 对已经缺失本地文件的失败附件，每次恢复都会再次清空 `retryAfter`，同一附件甚至会绕过文件复制本身的退避。
- 修复建议：返回“立即可继续”和“最早重试时间”两类调度结果，对未来附件任务使用延迟调度或真实 Worker 退避；恢复丢失文件只在首次发现时转移状态，保留失败状态已有的重试时间。

## P2：跨任务续扫没有整体完成轮次，两个大源集合会互相重开

- 位置：`app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MessageMirrorSynchronizer.kt:43-48`、`62`、`106-109`。
- 触发：SMS 与 MMS 各自的完整扫描都超过单次 120 秒预算。某次续扫完成一个源后，另一个源只能留下检查点；下一次虽然先续完后者，却又对前者从零新建扫描代次。由于完成判据要求两次 `scan` 在同一次 `reconcile` 都返回 true，这个库永远无法满足条件。集合 dirty 一直不被确认，Worker 不断续排，且 `readAncillarySources` 和定向 dirty 处理长期没有剩余预算。
- 修复建议：持久化一个覆盖 SMS/MMS/附属集合的整体对账轮次及各源完成标记；预算续任务跳过本轮已完成源。只有整体轮次结束或新的集合失效请求到来才开启下一轮完整扫描，并给 dirty 与附属集合分配可推进的阶段。

## P2：批量删除后的附件清理队列不能保证及时排空

- 位置：`app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MirrorAttachmentCopier.kt:108-116`；关联 `data/db/mirror/MirrorDao.kt:122`、`MessageMirrorSynchronizer.kt:62-66`、`75-78`。
- 触发：一次对账确认删除超过 600 个附件且没有其他待复制任务。一次 `reconcile` 清理 200 项，`recover` 再清理 200 项，空复制队列分支再清理 200 项，随后两个返回值都可以是 false。余下清理队列不会触发续任务，在没有新消息事件时只能等待次日周期任务；大量删除可能需要多日才能移除所有私有副本。
- 修复建议：在时间预算内按批推进清理队列，将剩余清理任务纳入调度结果。对真实删除失败单独设置重试时间，避免一个失败批次阻塞后续可删除项。

## P2：每个 Provider 事件追加一个完整扫描 Worker，批量操作产生任务积压

- 位置：`app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MirrorChangeObserver.kt:25-28`；关联 `worker/MessageMirrorScheduler.kt:15-19`、`MessageMirrorSynchronizer.kt:43`。
- 触发：批量导入、批量标记已读或持久化含多个 part 的 MMS 时，观察器每收到一次事件就调用 `schedule()`。`APPEND_OR_REPLACE` 只把这些请求串起来，没有合并：即使首个 Worker 已经排空持久 dirty 队列，其余排队 Worker 仍逐个重新完整扫描全部 SMS/MMS。因此 N 次事件可造成 N 次全量扫描，持续放大耗电与后续更新延迟。
- 修复建议：合并观察器唤醒，只保留一个正在执行及至多一个待执行任务；同时保留持久 dirty 与退出前检查/补唤醒，避免简单改为 KEEP 后丢失 Worker 退出窗口中的新事件。

## P2：附件流复制全程占用元数据同步锁

- 位置：`app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MirrorAttachmentCopier.kt:25-37`；关联 `MessageMirrorSynchronizer.kt:37`、`81-86`。
- 触发：一个大附件或慢 Provider 流需要较长时间读取。`copyPart` 在获取全局 `MirrorSynchronizationLock` 后才调用整个 `store.copy`，直到读取、落盘、摘要和绑定都完成才释放；同步器读取新短信、处理已读变化和确认删除也需要同一把锁。附件外层 120 秒预算只在调用前判断，不能限制单个复制过程，所以已持久化的 dirty 会被该文件完整读取阻塞。
- 修复建议：在短锁内领取带 localId/revision/partId 的复制任务，锁外流式复制，重新取得锁后校验版本并绑定。使用单独的附件任务互斥/在途登记保护临时文件，孤儿清理排除正在复制的文件；不匹配时只回收本次产物。
