# 数据版本驱动初始化实施计划

## 当前实现核对（2026-11-07）

本计划保留原实施记录与未验证清单。当前运行镜像库为 v7，初始化表仍来自 3→4 迁移；数据版本仍为 1。生命周期版本检查与升级 Worker 已落地，但原“列表纯本地”方案已被替代：首次订阅先同步，下拉与静默刷新调用增量同步。任务 6 已按当前代码重写，其他历史勾选不代表本次重新构建或真机验证。

> **For agentic
workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 在当前会话逐任务实施；若明确选择子代理方式，则使用 superpowers:subagent-driven-development。步骤使用复选框记录，完成需附实际验证证据。

**Goal:** 用独立数据版本控制一次性完整初始化；同版本生命周期检查不重复升级，列表读取和下拉按当前镜像同步契约工作。

**Architecture:** 在镜像数据库中保存升级状态，由唯一 WorkManager 任务协调镜像及派生索引。区分列表同步、生命周期升级检查和真实事件同步，复用现有镜像锁、dirty token、索引代次与附件解析能力。

**Tech
Stack:** Kotlin、Room、Coroutines/Flow、WorkManager、Koin、Jetpack Compose、PowerShell、ADB；JDK/JVM 21。

**Spec:** [已确认设计](2026-09-23-versioned-data-initialization-design.md)

## Global Constraints

- 未记录数据版本为 `0`，本次目标版本为 `1`，独立于 App 版本和 Room schema version。
- 全部必要阶段成功后才提交数据版本；关闭骚扰识别时合法跳过且记录原因。
- 会话列表下拉调用增量同步，结果通过现有订阅发布；原纯本地方案不再作为当前约束。
- 同版本生命周期入口不安排升级；进入列表的首次订阅仍执行要求完整的同步，真实事件、合法恢复任务及明确修复继续保留。
- 取消每日全量对账，取消历史唯一周期任务 `message-mirror-reconcile`。
- 智能卡片仍按需解析，不增加持久化卡片缓存。
- 不新增或运行单元测试，不新增测试依赖；使用编译、Lint、模拟器场景与只读数据库检查替代技能模板中的单元测试步骤。
- 不清空用户数据，不卸载应用，不新增网络下载，不记录短信原文。
- 所有源码、Git、构建和 ADB 操作在 Windows 项目 `D:\StudioProjects\PixelText` 运行。
- 临时日志、脚本、截图、数据库副本放 `docs/.local/`，正式资料不得依赖这些文件。
- 原实施记录中的模拟器为 `emulator-5554`；安装前核对 applicationId 和现有安装，不默认模拟器内数据可以删除。
- 不逐任务自动提交；交付前检查全部差异，提交动作按用户授权执行。

## 文件地图与依赖顺序

下面源码路径均相对 `app/src/main/java/vip/mystery0/pixel/text/`。任务中的路径前缀同此约定，不代表新建另一套目录。

| 文件                                                                      | 职责                                    |
|---------------------------------------------------------------------------|-----------------------------------------|
| `data/db/mirror/DataInitializationEntity.kt`（新增）                      | 唯一升级状态行                          |
| `data/db/mirror/DataInitializationDao.kt`（新增）                         | 状态观察、原子条件更新                  |
| `data/db/mirror/DataInitializationMigration.kt`（新增）                   | Room 3→4 非破坏性迁移                   |
| `data/db/mirror/MessageMirrorDatabase.kt`                                 | 注册 Entity、DAO、Migration             |
| `domain/model/DataInitializationState.kt`（新增）                         | 版本、阶段、UI 状态及阶段返回值         |
| `data/repository/initialization/DataInitializationRepository.kt`（新增）  | 持久状态契约和阶段检查点                |
| `data/repository/initialization/DataInitializationCoordinator.kt`（新增） | 顺序执行阶段、输入代次保护              |
| `data/repository/initialization/DataInitializationGuard.kt`（新增）       | 升级与恢复、规则替换的协调锁            |
| `data/repository/initialization/InitializationSpamScanner.kt`（新增）     | 可等待的历史自动识别重建                |
| `worker/DataInitializationScheduler.kt`（新增）                           | 检查版本及唯一任务安排                  |
| `worker/DataInitializationWorker.kt`（新增）                              | 权限、预算、重试映射                    |
| `data/repository/mirror/MessageMirrorSynchronizer.kt`                     | 可识别且可续跑的初始化对账轮次          |
| `data/repository/mms/MmsTextIndex.kt`                                     | 抽取可等待、分页的文本索引入口          |
| `data/db/mirror/MirrorDao.kt`                                             | 初始化索引分页所需的本地查询            |
| `PixelTextApp.kt`、镜像 Scheduler/Worker/Observer                         | 生命周期、历史工作与实时事件分流        |
| `ConversationCacheRepository`、消息 Repository、列表 ViewModel/Screen     | 镜像同步刷新与独立升级状态                |
| `data/repository/BackupRepositoryImpl.kt`、`HubResourceRepository.kt`     | 恢复和规则变化与升级阶段的协调          |
| `di/AppModule.kt`                                                         | Koin 接线，避免 UI 实例化带出同步副作用 |

依赖：任务 1 → 任务 2/3 → 任务 4 → 任务 5/6 → 任务 7。顺序实施，避免同步锁和阶段状态出现未整合的双重实现。

## 任务 1：持久化版本状态及数据库迁移

**文件：** 新增状态模型、Entity、DAO、Migration、状态仓库；修改 `MessageMirrorDatabase.kt`；生成 `app/schemas/vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase/4.json`。

**接口：** 后续阶段统一使用以下类型。`nextPhase` 是续跑游标，`status` 是展示状态；失败不能覆盖续跑游标。

```kotlin
const val CURRENT_DATA_VERSION = 1

enum class InitializationPhase { MIRROR, MMS_TEXT, SPAM, VERIFICATION, COMPLETE }
enum class InitializationStatus { PENDING, RUNNING, WAITING_PERMISSION, FAILED, COMPLETE }

data class DataInitializationState(
    val completedVersion: Int = 0,
    val targetVersion: Int = CURRENT_DATA_VERSION,
    val nextPhase: InitializationPhase = InitializationPhase.MIRROR,
    val status: InitializationStatus = InitializationStatus.PENDING,
    val epoch: Long = 0,
    val mirrorRound: Long? = null,
    val afterLocalId: Long = 0,
    val upperLocalId: Long? = null,
    val errorCategory: String? = null,
)

sealed interface InitializationStepResult {
    data object Complete : InitializationStepResult
    data object More : InitializationStepResult
    data class Blocked(val category: String) : InitializationStepResult
}
```

- [x] 检查现有 Room 3 schema；记录迁移前数据库表名、索引及 SMS/MMS 数量，不读取正文。
- [x] 定义单行 Entity，主键固定 1，字段与状态一致并增加 `updatedAt`、`skipReason`；枚举持久化为字符串。缺行映射缺省状态，不根据消息数量猜测版本。
- [x] 写显式迁移，只创建初始化状态表，不删除或重建镜像表：

```sql
CREATE TABLE IF NOT EXISTS data_initialization (
  id INTEGER NOT NULL PRIMARY KEY,
  completedVersion INTEGER NOT NULL,
  targetVersion INTEGER NOT NULL,
  nextPhase TEXT NOT NULL,
  status TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  mirrorRound INTEGER,
  afterLocalId INTEGER NOT NULL,
  upperLocalId INTEGER,
  errorCategory TEXT,
  skipReason TEXT,
  updatedAt INTEGER NOT NULL
)
```

- [x] 提供仓库 `observe(): Flow<DataInitializationState>`、`read(): DataInitializationState`、`prepare(targetVersion: Int)`、`save(expectedEpoch: Long, state: DataInitializationState): Boolean`。DAO 条件更新必须包含 `epoch` 和 `targetVersion`；受影响行数为 0 表示输入已失效，不得继续提交。
- [x] `prepare` 对已完成同版本只读；目标更高时初始化新阶段、递增 epoch；遇到更高已完成版本不降级、不清库。阶段切换重置阶段分页游标，保留旧 completedVersion。
- [x] 当时注册 Room 4、Migration 和 Koin 仓库（当前已继续升至 v7）。通过 `./gradlew.bat :app:compileDebugKotlin` 生成 schema，核对 3→4 只有预期新增表。

**检查点：** schema 无破坏性迁移；缺行、失败状态、阶段状态均不能映射为版本 1 已完成。

## 任务 2：让全量镜像成为可识别、可恢复的阶段

**文件：** `MessageMirrorSynchronizer.kt`、`MirrorSyncDao.kt`、初始化状态仓库。

**接口：**

```kotlin
suspend fun reconcileForInitialization(
    targetVersion: Int,
    epoch: Long,
    timeBudgetMillis: Long = 60_000L,
): InitializationStepResult
```

- [x] 阅读 `reconcile`、`scan`、`scanPass`、`readAncillarySources` 全部实现；确认已有 Boolean 返回值混合了 dirty 与清理状态，不能直接当作本阶段完成条件。
- [x] 在 `MirrorSynchronizationLock` 内用短数据库事务领取初始化轮次：将初始化状态的 `mirrorRound` 与新 `ROUND.generation` 一起保存。新目标不得借用升级前已经完成的旧轮次。
- [x] 拆出锁内对账实现供普通 `reconcile` 和新接口复用，避免再次获取同一非重入 Mutex。保留 checkpoint、budget_yield、删除复查和 dirty token 比较。
- [x] 初始化重试先检查其记录轮次的完成证据；已完成直接进入下一阶段，不因重试再新开完整扫描。扫描完成与阶段推进在同一锁内衔接，避免其他轮次覆盖完成证据。
- [x] 把 THREADS、CANONICAL 读取结果纳入完成判断；失败返回 Blocked 或 More。辅助源失败后重试辅助源，不重复扫描已成功的 SMS/MMS。
- [x] 阶段边界保存镜像最大 localId，供派生阶段有限遍历；对边界后的新消息继续依靠实时处理，不要求所有未来 dirty 都归零。
- [x] 记录短日志：`initialization mirror target=1 epoch=... round=... result=...`，不写正文或号码。
- [x] 编译，审查所有原 `reconcile()` 调用者仍保持原有修复、删除回调及附件清理行为。

**检查点：** 预算让出仍为旧数据版本；普通同步与升级不会自锁；空 SMS/MMS 可完成。

## 任务 3：提供可等待的派生阶段

**文件：** `MmsTextIndex.kt`、`MirrorDao.kt`、`InitializationSpamScanner.kt`；复用 `VerificationCodeRepositoryImpl.kt`、`SpamRepositoryImpl.kt` 和现有白名单/人工规则实现。

**接口：**

```kotlin
// 两个扫描器均按镜像 localId 分页，返回本批实际处理到的位置。
data class InitializationBatchResult(
    val afterLocalId: Long,
    val complete: Boolean,
    val unavailableCount: Int = 0,
)

// MmsTextIndexer 新入口
suspend fun indexInitializationBatch(
    afterLocalId: Long, upperLocalId: Long, limit: Int = 100,
): InitializationBatchResult

// InitializationSpamScanner 新入口
suspend fun scanBatch(
    afterLocalId: Long, upperLocalId: Long, limit: Int = 200,
    classifier: MirrorSpamScanSession? = null,
): InitializationBatchResult
```

- [x] 在 DAO 添加有限本地分页查询；复用 `MirrorMessageRecord` 映射，不为获取一批 MMS 装载全库：

```sql
SELECT * FROM mirror_message
WHERE transport = :transport AND localId > :afterLocalId AND localId <= :upperLocalId
ORDER BY localId LIMIT :limit
```

- [x] 从 MMS indexer 抽出单条“读取、解析、比较最新指纹、事务写入”操作，后台增量索引与初始化共同复用。初始化入口不得吞异常；索引已是有效版本及指纹时直接通过。
- [x] 读取需要附件的 MMS 前完成必要本地复制；仅执行本地复制，不调用 `MmsDownloadCoordinator` 开启下载。待下载 PDU 保留等待状态；现有永久损坏状态可产出不可用结果，瞬时读失败抛出供重试。不能将任意 `Throwable` 都视为永久损坏。
- [x] 消息在处理中被删除则跳过；指纹改变则不写旧结果、不推进越过未处理消息，下一批重读。批返回后才持久保存 `afterLocalId`。
- [x] 骚扰扫描沿用现有仅适用 SMS 的筛选、分类器及白名单，不清空人工规则、不重置白名单；查清人工结果存储位置，再使用既有自动结果写入接口。自动结果需要重算，不能用 identified IDs 排除旧自动结果。
- [x] 骚扰识别关闭：协调器记录 `skipReason=spam_disabled` 并跳过。开启时分类器分数小于 0 抛出明确错误，不增加成功计数。当前协调器每个切片复用 `MirrorSpamScanSession`，在 finally 关闭；扫描器未传 session 时自行用 `use` 管理，每批检查取消。
- [x] 验证码阶段直接等待 `VerificationCodeRepository.rebuildAll()`，不使用 Scheduler 的入队完成作为阶段完成。保留 activeGeneration 原子激活和异常清理。
- [x] 编译并核对无新增卡片持久化逻辑、无短信明文日志。

**检查点：** 已启动 indexer 不等于完成；索引失败、关闭和内容不可用三者有不同语义。

## 任务 4：唯一升级任务与并发输入保护

**文件：** 新增 Coordinator、Guard、Scheduler、Worker；修改 `BackupRepositoryImpl.kt`、`HubResourceRepository.kt`、`di/AppModule.kt`。

**接口：**

```kotlin
// DataInitializationCoordinator
suspend fun runSlice(timeBudgetMillis: Long = 60_000L): InitializationStepResult

// DataInitializationScheduler
fun checkOnLaunch()
fun retry()

// DataInitializationGuard
suspend fun <T> withStableInputs(block: suspend () -> T): T
suspend fun <T> withInputMutation(block: suspend () -> T): T
```

- [x] 建立唯一任务名 `data-initialization`；普通检查使用 KEEP，不因 Activity 恢复替换运行任务。执行前再次确认版本，已完成同目标立即结束，不解析。
- [x] `checkOnLaunch` 在后台只读状态和权限；版本落后才 prepare/入队，权限不足写 WAITING_PERMISSION；更高版本只展示兼容性问题。失败重试不能重置已完成阶段。
- [x] 协调器按如下顺序运行，阶段成功时才推进，最终提交包含 epoch 条件：

```text
MIRROR       -> reconcileForInitialization -> 保存边界 -> MMS_TEXT
MMS_TEXT     -> 有限批解析并保存游标        -> SPAM
SPAM         -> 有限批分类/明确禁用跳过     -> VERIFICATION
VERIFICATION -> 等待 rebuildAll 成功        -> COMPLETE + completedVersion=target
```

- [x] Worker 捕获 CancellationException 后重新抛出；SecurityException 映射 WAITING_PERMISSION；暂时数据库/I/O 错误记录 FAILED 并退避；不支持的高版本不自动重试。时间片未完成返回 More 并保留检查点；预算让出或阶段/游标推进时，使用 APPEND_OR_REPLACE 追加接续任务并返回 success；无推进才 retry 退避，不递归无限调用。
- [x] 升级切片在 Guard 的稳定输入区执行；备份恢复和规则替换持有同一输入变更锁。统一锁顺序为 Guard → 镜像锁或索引内部锁，禁止反向获取。
- [x] 输入变更只使“尚未完成的升级阶段证据”失效：递增 epoch 并清除受影响阶段检查点，保留 completedVersion。完整已完成同版本不因普通规则更新重启全量升级。
- [x] 备份恢复开始即持久标记升级输入失效，之后才恢复数据。进程中断后依据既有恢复保护状态阻止升级误提交；用户完成/确认恢复后再续跑。规则替换同样先失效后写文件，确保进程死亡不会复用旧证据。
- [x] Koin 注入不在构造函数内启动升级；Worker、Application 显式调用。记录阶段开始/结束及耗时，不记录短信。
- [x] 编译并审查重复启动、规则更新、恢复三者的锁顺序和 epoch 更新路径。

**检查点：** 同目标最多一个升级任务；阶段失败不写版本；恢复中断和规则变化不误提交旧解析结果。

## 任务 5：移除启动与周期扫描，保留实时事件

**文件：** `PixelTextApp.kt`、`MirrorChangeObserver.kt`、`MessageMirrorScheduler.kt`、`MessageMirrorWorker.kt`、`ConversationCacheRepository.kt`、`VerificationCodeIndexScheduler.kt`、Koin 接线。

- [x] 先记录旧调用链作为静态失败证据：Application 无条件 `scheduleReconcile()`、`ensurePeriodic()`、`schedule(forceReconcile = true)`，Observer.start 主动 markWake，缓存 startObserving 主动 schedule。
- [x] Application 保留观察者注册，改为 `DataInitializationScheduler.checkOnLaunch()`；移除启动验证码对账。权限变化后的恢复入口仍检查版本，但同版本分支只读。
- [x] Observer.start 只注册 ContentObserver，删除注册时人工 wake；onChange 仍持久写入精确 dirty 或真实增量 wake，再安排元数据任务。
- [x] 缓存 startObserving 只注册，不调度。取消旧周期任务并删除 ensurePeriodic 注册；不取消验证码清理或资源更新等无关周期任务。
- [x] 给新镜像任务增加明确的调度原因，例 `provider_event`、`local_write`、`manual_repair`、`restore`；更新所有调用者。Worker 不再把“被唤醒”本身解释为强制全量。
- [x] 旧任务没有原因时不能可靠区分历史生命周期与真实事件；采取保守兼容：只取消明确的旧周期工作，旧一次性任务有持久 dirty 或恢复证据时允许收尾，无证据时直接结束。禁止清空混合来源的 dirty。日志标记 `legacy_pending`，与新启动触发区分。
- [x] 本地发送/接收、已读、删除和附件更新继续保留调度；事件期间发现需要初始化时交由升级协调器，不让多个独立全量流程争抢初始化所有权。
- [x] 搜索全部 `forceReconcile`、`scheduleReconcile`、`markWake`、`ensurePeriodic` 调用，逐项标注触发源；生命周期版本检查不直接扫描；当前列表订阅仍可进入同步器，须单独追踪该路径。
- [x] 编译并记录静态检查结果。

**检查点：** 生命周期不强制全量；列表同步与升级职责区分；移除周期任务不删除真实事件，明确修复仍有效。

## 任务 6：列表同步与独立升级 UI（原纯本地方案已被替代）

**文件：** `domain/repository/MessageRepository.kt`、`MessageRepositoryImpl.kt`、`ConversationCacheRepository.kt`、`ConversationListViewModel.kt`、`ConversationListScreen.kt`。

原实施清单中“删除 refreshConversations、下拉只调用 first()、任何状态均不触发同步”的完成标记已不适用于当前实现，故以当前代码事实替换，不将这些旧完成项作为当前验收证据：

- `MessageRepository.refreshConversations()` 现已存在，实现调用 `ConversationCacheRepository.refreshIncremental()`；`forceSyncConversations()` 仍为显式修复入口。
- `getAllConversations()` 的 Flow 开始先调用 `refreshIncremental(requireComplete = true)`，之后观察镜像、设置与联系人变化并从 SQL 查询会话。
- `ConversationListViewModel.refreshLoaded()` 同时服务下拉和静默刷新，调用 `refreshConversations()`；现有订阅发布结果，不重新 `first()` 建立全量查询。指示器等待同步调用结束，并保留取消传播和错误日志。
- 未就绪镜像可能先对账；空库和失败状态不能描述为纯本地回退。同步可能竞争镜像锁，也可能调度后续镜像任务。
- 升级状态及进度独立观察；权限不足、失败重试和高版本提示仍由初始化状态控制，列表发射不代表升级结束。
- MMS indexer 由明确后台/升级入口启动；数据版本完成后 Worker 启动 indexer 并调度 `initialization_complete` 收尾工作，不以入队作为阶段完成证据。

**当前检查点：** 对首次读取、下拉和静默刷新分别核对同步调用、错误与 UI 状态；本次仅静态核查，未重新执行设备回归。

## 任务 7：模拟器验证、回归与交付（部分场景完成）

**文件：** 仅按发现的问题调整相关源码；运行记录、截图和一次性脚本放 `docs/.local/versioned-initialization/`。不创建 test/androidTest 源码。

- [ ] 先确认安装包与设备信息：

```powershell
Set-Location D:\StudioProjects\PixelText
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell pm list packages | Select-String 'pixel.text'
Get-Content app/build.gradle.kts -Encoding utf8 | Select-String 'applicationId|applicationIdSuffix'
```

- [ ] 保存旧版可观察基线：默认 SMS 角色、应用版本、镜像记录计数、冷启动画面和同步日志。只记录状态与计数；数据库副本不得提交。优先保留原应用数据进行升级安装，不执行 `pm clear` 或卸载。
- [ ] 执行验证构建：

```powershell
./gradlew.bat :app:compileDebugKotlin :app:lintDebug :app:assembleDebug
```

- [ ] 检查生成 APK 的实际路径及 applicationId，再使用 `adb -s emulator-5554 install -r <实际APK路径>` 覆盖安装。签名不匹配则停止报告，不以卸载解决。
- [ ] 验证旧库 0→1：观察各阶段开始/完成、版本最终提交；核对消息数量和已有归档、白名单等设置保留。确认迁移后没有 schema 校验崩溃。
- [ ] 初始化完成后 force-stop 并启动三次，每次记录开始时间与阶段日志：

```powershell
adb -s emulator-5554 shell am force-stop vip.mystery0.pixel.text
adb -s emulator-5554 shell am start -W -n vip.mystery0.pixel.text/.MainActivity
adb -s emulator-5554 logcat -d -v time -s DataInitialization MessageMirrorSync MessageMirrorFastSync VerificationCodeIndexWorker
```

若 Debug applicationId 不同，用上一步确认值替换。当前预期没有重复升级阶段、启动验证码全量重建及首次升级弹窗；列表同步产生的 Provider 查询或 scan batch 须记录来源，不再要求启动零扫描。过滤历史日志时按每次启动时间截取，不清空设备全部日志作为必要步骤。

- [ ] 获取屏幕尺寸和 UI 层级，定位会话列表后执行下拉；不要硬编码未经检查的坐标。记录转圈结束时间、镜像锁等待和任务日志。初始化中和失败状态也验证增量同步/必要对账路径，不能继续按纯读取验收。
- [ ] 用一条合成 SMS 验证真实事件：

```powershell
adb -s emulator-5554 emu sms send 15555215554 "synthetic initialization event 001"
```

预期短信入库并展示；再通过 UI 标记已读、删除该合成消息，核对本地列表变化。不得批量删除原有消息。

- [ ] 在运行阶段 force-stop 后重新启动，确认旧版本仍未提前提交，任务续跑；用模拟器权限 UI 撤销再授予短信权限，确认 WAITING_PERMISSION 和恢复。为可靠覆盖短阶段，可使用仓库外临时调试构建注入延迟/失败，交付代码必须移除注入。
- [ ] 空库、目标版本 2、高版本拒绝降级、备份恢复并发、规则替换并发及异常 MMS 在独立可丢弃模拟器或经授权的数据副本验证，不篡改用户当前模拟器原库。无安全环境则明确列为未验证项。
- [ ] 7000+ 条性能验证优先使用已有合成数据。若当前模拟器不足，不擅自向其注入数千条数据；另建隔离模拟器或取得数据填充授权。报告真实样本规模，不能用小样本声称通过大库性能验证。
- [ ] 检查 WorkManager 周期项已取消；检查成功数据版本保持 1。运营商网络下载、双卡和真机后台行为仍需真机，模拟器验证不能替代。
- [ ] 最终执行 `git diff --check`，检查新增 schema、Koin 构造参数和全部变更；确认无原始短信、截图、临时脚本、测试依赖及无关格式化进入正式差异。

## 覆盖映射与执行检查点

| 设计要求                           | 任务 |
|------------------------------------|------|
| 版本 0/1、降级保护、非破坏性迁移   | 1、7 |
| 全量镜像、阶段续跑、辅助源成功条件 | 2、4 |
| MMS 索引、骚扰重建、验证码代次     | 3、4 |
| 失败、权限、唯一任务、取消传播     | 4、7 |
| 取消启动/周期同步、真实事件继续    | 5、7 |
| 下拉同步、UI 不混淆扫描与升级状态  | 6、7 |
| 备份恢复、规则更新输入失效         | 4、7 |
| 性能证据、隐私及设备限制           | 7    |

执行建议检查点：任务 1–3 后检查持久化及阶段契约；任务 4–6 后检查全链路接线；任务 7 后汇总通过项、失败项、未验证项。不把写完计划或编译通过视为功能完成。
