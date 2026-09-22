# Issue #16：设置与 SMS 备份恢复实施计划

> 执行者逐项实施并记录证据；按当前会话授权选择直接执行或协同，不自动启动 worker，不自动提交。任务勾选仅代表实现和相应验证均完成。

**目标：** 实现本地设置、规则及 SMS 备份恢复，支持可选加密、非破坏合并与安全中断。

**架构：** Domain 描述稳定备份模型及操作状态，Data 复用现有镜像同步，从应用数据库生成一致快照；恢复读取隔离备份库并通过 Provider 合并写回，分离容器、版本适配、数据映射和安全协调。Compose/StateFlow 展示预览和进度，应用作用域协调器持有任务与操作互斥，所有新增对象接入现有 Koin。

**技术栈：** Kotlin、Compose Material 3、Coroutines/Flow、Moshi、Room/SQLite、Android SAF/Telephony、计划引入 Zip4j ZIP AES。

**设计：** [备份恢复设计](2026-09-22-issue-16-backup-restore-design.md)。用户已确认首版 SMS、合并恢复、可选加密及总体方案。

## 全局约束

- 全部源码路径以下列 `B` 为前缀：`app/src/main/java/vip/mystery0/pixel/text/`。
- 首版不备份 MMS/附件，不清空现有短信，不自动发送恢复的出站记录，不主动联网传输。
- 备份应用自身数据，不复制系统数据库，不新增独立的 Provider 导出扫描器。先完成 SMS 镜像同步，再生成一致、裁剪后的应用数据库快照；备份库只在隔离区读取，不替换运行库。
- 短信/密码不得进入日志、Git、WorkManager Data 或 SavedStateHandle。
- 不新增 `app/src/test/`、`app/src/androidTest/`、测试依赖，不运行单元测试任务。
- 工作记录、合成样本和设备截图放 `docs/.local/`，正式契约放 `docs/development/`。
- 不改 Android 系统备份规则，不无关重构。所有错误必须区分校验前零写入与执行后部分完成。
- 编译与 Lint 在远程 Windows 根目录使用 `./gradlew.bat`。涉及 SMS、SIM、角色的结果必须真机确认。

## 文件职责

新增路径均位于 B 下：

| 路径 | 职责 |
| --- | --- |
| `domain/backup/BackupModels.kt` | 类别、预览、阶段、计数、结果 |
| `domain/backup/BackupRepository.kt` | UI 可调用的操作契约 |
| `data/backup/BackupPayloads.kt` | manifest、设置和恢复读取模型，不另定义 SMS NDJSON 格式 |
| `data/backup/AppDatabaseSnapshotter.kt` | 应用库一致读事务、允许表/行过滤及干净快照生成 |
| `data/backup/BackupDatabaseReader.kt` | 不可信备份库校验、版本适配、隔离读取与必要迁移 |
| `data/backup/BackupArchiveCodec.kt` | 版本化 ZIP、密码、校验和、限额 |
| `data/backup/BackupSettingsMapper.kt` | 可迁移设置及主题/背景映射 |
| `data/backup/BackupRuleStore.kt` | 关键词/白名单并集与状态关联 |
| `data/backup/SmsRestoreDataSource.kt` | 恢复时查询目标 Provider 用于去重、字段白名单写入及新 ID 查询 |
| `data/backup/SmsRestoreMatcher.kt` | 多重集合匹配、临时磁盘索引、ID 映射 |
| `data/backup/RestoreSafetyCoordinator.kt` | 持久化恢复保护与删除批次互斥 |
| `data/backup/BackupOperationJournal.kt` | 阶段/计数与中断状态，禁止存密码 |
| `data/repository/BackupRepositoryImpl.kt` | 操作编排及应用作用域任务 |
| `viewmodel/BackupViewModel.kt` | 用户输入/操作映射、StateFlow |
| `ui/screen/BackupRestoreScreen.kt` | SAF、类别、加密、预览、进度、结果 |

修改已有文件围绕 AppSettings/Theme/Smartspacer 仓库、Spam/Whitelist/Archive/Mirror DAO、现有镜像同步锁和完成状态、清理与同步链路、`di/AppModule.kt`、`ui/AppNavigation.kt`、`ui/screen/SettingsScreen.kt`、`PixelTextApp.kt`。不要把所有备份逻辑堆入 SettingsViewModel 或 TelephonyDataSource。

## 任务 1：稳定模型及容器读写

**文件：** 新增 BackupModels、BackupRepository、BackupPayloads、BackupArchiveCodec、BackupDatabaseReader；修改 `gradle/libs.versions.toml`、`app/build.gradle.kts`；新增 `docs/development/backup-format.md`。

**接口：** 后续任务共同使用下列 Domain 合同。password 仅当前调用内使用，调用方和实现方在操作完成后清理引用，不放到数据类或日志中。

```kotlin
enum class BackupSection { SETTINGS, RULES, SMS }
enum class BackupPhase {
    IDLE, SYNCING_MIRROR, SNAPSHOTTING, EXPORTING, VALIDATING, PREVIEW, RESTORING,
    REBUILDING, COMPLETED, INTERRUPTED, FAILED
}
data class BackupSelection(val sections: Set<BackupSection>)
data class BackupPreview(
    val token: String,
    val availableSections: Set<BackupSection>,
    val smsCount: Long,
    val warnings: List<String>,
)
data class BackupSummary(
    val inserted: Long,
    val existing: Long,
    val failed: Long,
    val remaining: Long,
    val convertedOutgoing: Long,
    val skippedAssociations: Long,
    val completedSections: Set<BackupSection>,
)
data class BackupOperationState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val processed: Long = 0,
    val total: Long? = null,
    val preview: BackupPreview? = null,
    val summary: BackupSummary? = null,
    val errorMessage: String? = null,
)
interface BackupRepository {
    val state: StateFlow<BackupOperationState>
    suspend fun exportTo(uri: String, selection: BackupSelection, password: CharArray?)
    suspend fun inspect(uri: String, password: CharArray?)
    suspend fun restore(token: String, selection: BackupSelection)
    fun cancel()
    suspend fun acknowledgeResult(disableVerificationCleanup: Boolean)
}
```

- [ ] 定义 manifest/settings DTO 和备份数据库读取模型；manifest 记录容器版本、各库版本/结构标识、类别计数、快照及 SMS 同步时间。以快照镜像 localId 作为 backupId，旧 sourceId/threadId 仅供包内关联。规则和短信保存在裁剪后的应用 DB，不另导出 JSON/NDJSON。
- [ ] 核验 Zip4j 的稳定版本、许可证、Android 最低版本与 AES 支持，锁定版本，不依赖动态版本。确认其输出加密和输入认证错误可以传播到协调器。
- [ ] 实现固定白名单 ZIP 条目：manifest.json、settings.json、databases/message_mirror.db、databases/spam.db、databases/conversation_archive.db、引用的 theme 资源；对已关闭的快照文件流式计算 SHA-256，校验类别计数，最后生成 manifest。
- [ ] 加密时所有有效载荷使用 AES-256；不加密时保持同样容器结构。不得回退到 ZipCrypto。
- [ ] inspect 只写私有暂存区，读取所有条目到结尾以验证认证码、哈希、记录数和格式；限制实际解压总量、条目数和每条记录大小；拒绝路径穿越、重复和未知条目。
- [ ] 在 BackupDatabaseReader 中只读检查数据库版本、允许表/列/索引、完整性/外键及记录语义；拒绝意外视图、触发器、虚拟表和运行态数据，限制扫描资源和取消响应。不执行备份提供的任意 SQL。
- [ ] 明确容器与数据库版本支持矩阵；旧库用读取适配或仅在隔离副本中执行已注册迁移，重新验证后才使用。未知更高版本拒绝，禁止破坏性重建及应用业务初始化回调。
- [ ] inspect 成功生成随机 token，仅引用当前已校验的私有快照；备份库不注册到当前镜像同步器。restore 不重新读取可能被替换的外部 URI；token 不跨进程重用。
- [ ] 实现暂存文件生命周期，取消、认证失败和后续启动清理残留；导出完整关闭前不报告成功。
- [ ] 执行 `./gradlew.bat :app:compileDebugKotlin`；用后续页面或本地临时验证入口生成合成空包、加密包、截断包，检查错误密码与畸形条目被拒绝。没有完成实际往返验证时保持本项未勾选。

**验收：** 加密与非加密包可往返；文件损坏、超限、错误密码无业务写入。正式文档包含字段、限制和兼容政策，不只描述扩展名。

## 任务 2：设置、主题与规则迁移

**文件：** 新增 BackupSettingsMapper、BackupRuleStore；修改 AppSettingsRepository/Impl、ThemeConfigurationRepository/Impl、ThemeAssetRepository/Impl、UnreadSmsComplicationSettingsRepository、SpamDatabase、SenderWhitelistDao 及相关仓库。

**输入：** 任务 1 的设置 DTO 和隔离数据库读取模型；**输出：** 设置快照、供任务 3 写入快照库的规则/状态行，以及可检查失败的合并操作，不直接修改 UI 状态。

- [ ] 核对设置 UI、AppSettings 和主题字段，正式文档逐项标注“迁移/不迁移及原因”；保留资源自动检查偏好，排除版本、缓存和提示状态。
- [ ] 使用字段白名单生成设置 DTO，避免未来新增运行态字段自动进入备份。恢复缺失可选字段保留目标值，已包含字段按已验证值覆盖。
- [ ] 给设置仓库增加批量、同步确认持久化结果的入口，沿用 setter 的约束与状态通知；失败不发布虚假成功 StateFlow。
- [ ] 背景仅复制当前配置引用的资源；导入先验证并生成新资产，再提交主题配置，失败清理新资产，不能先删旧背景。
- [ ] Smartspacer 只导出默认筛选，恢复不写旧 smartspacerId 的 scoped key。
- [ ] 关键词以 trim + lowercase(Locale.ROOT) 取并集，白名单按 type/value 取并集；全量预校验规则、正则和 500 条上限，超限在该类别写入前拒绝，不静默截断。
- [ ] 从 spam/归档库在一致读事务内读取选中类别；与任务 3 的 SMS 来源身份核对，只输出可关联状态，关联失败计入 skippedAssociations。跨库不承诺全局事务，禁止将旧 sourceId 直接当目标 ID。
- [ ] 恢复时从隔离快照读取规则，在运行 Room 库事务内合并，不复制主键、不覆盖整个 DB。单条放行和归档延迟到任务 3 产生目标 ID 映射后处理。
- [ ] 编译后使用合成配置核对暗/亮主题、背景存在/缺失、通知操作顺序、重复规则、非法正则、已有目标设置不丢失。

**验收：** 选中类别覆盖或合并正确，未选中类别不变；未选设置时不影响系统权限与本地资源版本；失败有真实持久化结果。

## 任务 3：应用数据库快照、SMS 匹配及 Provider 写回

**文件：** 新增 AppDatabaseSnapshotter、SmsRestoreDataSource、SmsRestoreMatcher；复用 `data/db/mirror/MessageMirrorDatabase.kt`、`MirrorEntities.kt`、`MirrorDao.kt`、`MirrorSyncDao.kt`、`data/source/mirror/ProviderRowSnapshot.kt` 和镜像两个 Synchronizer；在 BackupRuleStore 接入状态映射。

**输入：** 应用当前库及任务 2 的规则/状态行；恢复读取任务 1 验证过的隔离备份库。**输出：** 裁剪后的应用 DB 快照、恢复计数及 backupId 到目标 ID/threadId 的临时映射。

- [ ] 复用镜像同步执行新一轮 SMS 全量对账及相应脏记录处理，明确本次 SMS 完成凭据。预算让出继续现有流程，同步失败/权限丢失/取消则终止备份，不使用旧 complete 标记冒充成功；不等待 MMS 下载或要求 MMS 同步完成。
- [ ] 在现有镜像同步锁与读事务保护下，按 localId 分批读取 SMS 父子行，包含源库 WAL 中已提交数据；写入新的暂存库，不新建从 Provider 导出短信的扫描器。
- [ ] 复用受支持版本的表结构，只向干净的新库写入允许表/行，保留版本元数据。禁止复制整库再仅 DELETE；MMS、附件、同步进度、脏队列、下载任务、分类缓存必须无数据且无空闲页残留。仅规则备份不得带入 SMS/单条放行，SMS 备份未选规则时不得顺带复制关键词/白名单。
- [ ] 快照库提交并 checkpoint/关闭，检查仅主文件即可读取、无外部 WAL/SHM 依赖；完成快照即释放锁，不在打包期间阻塞同步。取消/磁盘不足删除不完整暂存库，不发布成功。
- [ ] 从隔离快照分批读取 Mirror SMS 字段/rawSnapshot，映射允许写入列，区分缺列与 NULL。rawSnapshot 禁止全部直接转换成 ContentValues；不恢复 creator、系统 ID 或旧订阅身份。
- [ ] 将目标消息匹配索引写入私有临时 SQLite，以内容身份和出现序号匹配，不把全量正文放入内存集合。摘要命中后核对精确原字段。
- [ ] 按下列多重集合规则生成目标映射，插入失败不能计入成功：

```text
对每个备份消息键 K：
  获取目标同键消息，按目标 ID 排序
  备份同键第 n 条优先匹配目标第 n 条
  若目标不足 n 条，则插入本条并加入本次目标列表
  无论匹配还是新增，记录 backupId → 实际目标 ID/threadId
```

- [ ] 入站、已发、草稿、失败保持类型；出站/发送中转失败，同时规范化匹配类型，使再次导入不会不断重复。Provider 拒绝无地址草稿时作为明确失败报告，不偷偷丢弃。
- [ ] ContentValues 不写旧 `_id/thread_id/sub_id`；订阅显式使用有效的未知值。调用 Provider 后查询目标 threadId，不能从来源猜测。
- [ ] 已有匹配短信不覆盖 read/seen。新增消息保留原 read/seen/date/dateSent，锁定等可选列按目标 Provider 支持处理并报告降级。
- [ ] 按目标消息身份重建单条放行指纹，归档根据实际会话重新查询快照；不把 MMS 旧 ID 混入 SMS 映射。
- [ ] 编译；在可写 SMS 的测试设备上用合成短信验证同键多条、不同日期/方向、空 body、无地址草稿、双 SIM 来源、已有目标消息、连续导入两次及写入后进程中断重试。

**验收：** 快照完整、一致且不含未选类别/运行态数据；源库有 WAL 时记录不丢失。备份库不替换当前库；写回保留真实重复数量，重复导入不增长，无发送副作用；不能精确匹配时保守处理，不误删目标消息。

## 任务 4：恢复安全、中断与编排

**文件：** 新增 RestoreSafetyCoordinator、BackupOperationJournal、BackupRepositoryImpl；修改 `worker/VerificationCodeCleanupWorker.kt`、必要的索引/同步入口、`PixelTextApp.kt`、`di/AppModule.kt`。

**输入：** 任务 1–3 的数据操作；**输出：** Domain BackupRepository 和恢复保护状态，清理任务共享同一保护协调器。

- [ ] 在应用作用域创建单任务互斥、IO Job 和 StateFlow；export/inspect/restore 不依赖页面实例存活。重复点击返回“已有操作进行中”。
- [ ] 备份编排为镜像同步 → SMS 完整性确认 → 数据库/设置/主题快照 → 校验 → 打包输出，分别发布 SYNCING_MIRROR/SNAPSHOTTING/EXPORTING。仅设置/规则备份跳过短信同步。
- [ ] 使用私有持久化日志记录恢复阶段和计数，不记录密码、短信正文。启动时发现中断日志发布 INTERRUPTED，并保留删除保护。
- [ ] 将恢复启动与验证码删除批次放在同一互斥边界，按以下语义实现；不要先检查布尔值再脱锁删除：

```text
beginRestore:
  在 deletionGate 中同步持久化 active=true
  持久化失败则禁止第一条业务写入
cleanupBatch:
  在 deletionGate 中检查 active
  active 时跳过；否则在 gate 内执行当前删除批次
finishRestore:
  导入及重建完成后等待用户确认
  用户选择关闭清理时先持久化设置，再清除 active
```

- [ ] 核查 `SpamDetectionWorker.kt`、`HistoricalSpamScanWorker.kt`、`KeywordSpamRebuildWorker.kt`、`VerificationCodeIndexWorker.kt` 的真实入口；导入不调度收信 worker，不显示历史消息通知，不应用垃圾短信自动删除。
- [ ] 检查 `data/repository/mirror/MirrorChangeObserver.kt` 和两个 MirrorSynchronizer，合并导入期间刷新，写回后从目标 Provider 对账当前镜像、重建索引。备份库始终隔离，不能替换运行库或受其清理；真实新消息正常接收，不全局关闭收信或所有 Provider 观察。
- [ ] 依设计顺序编排规则、短信、状态、设置、重建；在每批 SMS 写入前复核权限和默认短信资格，角色丢失停止后续批次。
- [ ] 对取消和失败保存部分结果，不回滚删除已经恢复的短信，不自动无限重试。finally 清理暂存密码引用和可清理文件，但不能无条件解除尚未确认的删除保护。
- [ ] 重启清理遗留明文快照；中断恢复要求重新选择文件/输入密码。用户可以重新导入或确认保留部分数据后解除保护，不复用失效 token。
- [ ] 编译与 Lint；真机同时触发验证码清理、导入、真实新短信，验证不会出现“刚导入即被清理”、历史通知轰炸或清理永久无提示停用。

**验收：** 数据写入之前已建立可持久化保护，批次竞态被锁约束；结束/取消/角色丢失/进程死亡均有可理解结果。

## 任务 5：设置入口与完整交互

**文件：** 新增 BackupViewModel、BackupRestoreScreen；修改 SettingsScreen、AppNavigation、AppModule。

- [ ] 设置页增加“备份与恢复”入口，独立路由承载功能；避免把业务塞入 SettingsScreen。
- [ ] 接入 CreateDocument/OpenDocument，URI 交给 Data 使用 ContentResolver；不申请广泛存储权限，不要求真实文件路径。
- [ ] 提供 SETTINGS/RULES/SMS 类别选择、加密开关及二次确认密码；未加密导出明确提示文件持有者可读取内容。
- [ ] 密码不用 rememberSaveable；配置变化允许重新输入，但不丢失运行中的操作状态。导入完成校验后展示包含类别、数量、时间和限制提示，再允许确认写入。
- [ ] 仅 SMS 操作请求读取权限/默认短信资格，不因设置恢复阻塞整个页面；角色请求取消后保留预览，不自动执行恢复。
- [ ] 按 StateFlow 显示镜像同步、数据库快照、输出、恢复进度及部分结果/关联跳过数量；同步失败不允许静默导出旧镜像。提供取消和重新导入入口，提示仅 SMS、取消不撤销写入、不迁移原 SIM、出站转失败及混合会话归档影响。
- [ ] 结果确认时如清理已启用，提示旧验证码之后可能被常规清理并提供关闭选项；检测中断状态时展示恢复或结束保护的入口。
- [ ] 页面使用 MaterialTheme.colorScheme，核对暗色、动态取色、大字体、返回/旋转及双击操作。

**验收：** 用户可以无命令行完成备份→换设备/清洁安装环境→预览→恢复，不误认为支持彩信或保证后台连续执行。

## 任务 6：验收与交付

- [ ] 更新 `docs/development/backup-format.md`，记录实际字段、加密格式、限额、迁移行为及失败语义，核对与实现一致。
- [ ] 执行 `./gradlew.bat :app:compileDebugKotlin :app:lintDebug`，按需 `./gradlew.bat :app:assembleDebug` 安装；不运行单元测试。
- [ ] 用合成数据记录以下矩阵，真实短信正文不进入证据：

| 场景 | 预期 |
| --- | --- |
| 空历史、仅设置、仅规则、完整 SMS | 正确类别和计数，未选数据不变 |
| 镜像落后、同步失败、预算让出、MMS 未完成 | 新 SMS 同步完成后才备份；失败不冒充完整；不依赖 MMS 下载 |
| 源库 WAL 未 checkpoint、并发写入 | 快照包含已提交记录、父子关系一致，不靠外部 WAL 读取 |
| 裁剪库及空闲页检查 | 无 MMS、未选类别和运行态任务数据残留 |
| DB 损坏/未知版本/异常结构 | 业务写入前拒绝，不执行任意 SQL |
| 支持的旧库迁移、当前镜像正在同步 | 仅隔离适配/迁移，备份源不被清理，运行库不被替换 |
| 从快照恢复完成 | Provider 写回正确，当前镜像对账一致 |
| 加密/明文往返、Unicode 密码 | 两种容器可读，错误密码零业务写入 |
| 损坏/截断/路径穿越/重复条目/高版本/超限 | 写入前拒绝 |
| 同键真实重复、连续导入、部分成功重试 | 数量保持，无重复增长 |
| 出站/草稿/已读/未读/失败/锁定 | 状态按契约处理，不发送 |
| 双卡来源→单卡/无卡设备 | 不误套源 subId，可读取恢复消息 |
| 取消、角色切换、磁盘不足、杀进程 | 部分结果准确，重新导入安全 |
| 恢复期间验证码清理、真实新收信 | 保护导入数据，不破坏真实收信 |
| 大量 SMS、背景图片、旋转/返回 | 内存有界，进度连贯，临时资源清理 |
| 结束保护后常规清理 | 提示与设置选择生效 |

- [ ] 对系统写入和角色链路提供真机证据；没有 API 31 或目标版本设备则显式列为未验证，不能宣称完全兼容。
- [ ] 自查无业务明文日志、密码持久化、旧 ID 复制、非法文件任意解压及恢复期间删除竞态。
- [ ] 检查 `git diff --check` 与最终改动清单，排除临时样本、日志、构建产物及 IDE 文件；说明已验证/未验证项，不自动提交。

## 计划自检

设计覆盖：容器/加密/备份库校验与版本兼容→任务 1；设置/主题/规则→任务 2；应用库一致快照/裁剪/SMS 写回/去重/映射→任务 3；同步编排/安全/中断/当前镜像对账→任务 4；用户交互→任务 5；快照及设备验证→任务 6。

主要风险是镜像完整性与一致快照、备份数据库版本兼容、系统 Provider 设备差异、清理并发窗口和跨存储部分成功；应先验证这些路径，再进行页面视觉细节。任务 2 和任务 3 可在稳定契约后独立推进，但本计划不要求启动并行代理。
