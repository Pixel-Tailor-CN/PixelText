# 全文识别与端侧二次学习：客户端实施计划

> 执行约定：依据本仓库设计，使用 executing-plans 按任务实施；只修改、构建和评审本仓库。项目禁止 App 单元测试的约定优先于通用技能，不因技能模板逐步提交或重复审批。

**目标：** 完整短信参与识别，用户主动纠正后本地学习，确认后才能贡献样本。

**架构：** Domain 统一判定和学习契约，Data 管理私有数据、模型快照和资源更新，Worker 执行持久任务，Compose 与 StateFlow 展示交互。个人参数独立于基础资源，版本组合原子切换。

**技术：** Kotlin、Compose、Room、Coroutines/Flow、Koin、WorkManager、TFLite。

**设计：** [客户端设计](2026-11-01-on-device-learning-design.md)。下文代码路径以 `app/src/main/java/vip/mystery0/pixel/text/` 为根，特别注明者除外。

> 当前状态：全文识别、独立学习库、学习调度、判定接入和贡献页面已实现。下方任务保留实施拆分；实现勾选仅表示代码存在，不替代设备、性能或故障场景验证。早期自动脱敏方案已撤回，当前使用手动辅助脱敏。

## 全局约束

- 二次学习默认 false，贡献草稿不自动脱敏；完整码点输入，不静默截断；阈值 0.7。
- 白名单／持久放行 > 关键词 > 当前消息人工纠正 > 个人预测 > 基础预测。
- 只在确认上传后发请求；学习与贡献相互独立；所有历史重算无已读、删除或通知副作用。
- 不新增 App 测试目录、测试依赖或单元测试任务。中文文档／注释，英文日志，无短信原文。
- 文档、代码注释、提交和发布说明只包含客户端及必要公开协议，不写私有工程名称、路径、生产训练流程或服务内部实现。
- 启动时重读 git status，保留现有修改；不自动提交、推送或发布。外部产物不齐不跨仓实现，先使用本仓库合成契约样件。

## 重点验收

1. 第 40 字后才不同的长文本、补充平面字符和空白不能被裁切或错码（任务 1、8）。
2. 训练完成与关闭、删样本、资源升级并发时，旧结果不能重新生效（任务 3、4、8）。
3. 规则冲突和 Provider ID 复用不能触发错误已读／删除（任务 2、5、8）。
4. 上传取消、编辑和未知结果重试不能发送未经确认的内容（任务 7、8）。
5. 资源下载／激活时崩溃不能产生混合模型、词表和个人参数（任务 4、8）。

## 任务 1：全文运行时和公开契约

实现文件：`domain/spam/SpamClassifier.kt`、`SpamClassifierFactory.kt`、`di/AppModule.kt`、`domain/spam/learning/SpamModelEngine.kt`、`data/spam/learning/TfliteSpamModelEngine.kt`。学习模型类型集中在 `domain/spam/learning/PersonalLearningRepository.kt`，没有独立 `LearningModels.kt`。依赖版本见根目录 `gradle/libs.versions.toml` 和 `app/build.gradle.kts`。

实际接口：模型身份为 `String`；`PersonalParameters(identity, generation, payload: FloatArray)` 保存在学习仓库模型文件。`SpamModelEngine` 暴露 `identity`、`modelVersion`、`parameterCount`、`epochs`，以及 `normalize(text)`、`reset()`、`train(text, label, parameters)`、`inferPublic(text)`、`inferPersonal(text, parameters)` 和 `close()`。整批样本循环、取消检查和代次提交由 `PersonalLearningCoordinator` 负责；`SpamClassifier.kt` 定义 `SpamPrediction(score, source, modelIdentity, personalGeneration, revision)` 以及批次 `SpamPredictionSession`。不存在原草案中的独立 `ModelIdentity` 或引擎批量 `train(samples, cancellationCheck)` 接口，资源读写仍由 Data 负责。

- [x] 固定公开样件的签名、动态形状、预处理、参数格式和训练配置；未知版本明确拒绝。若样件无法运行，记录具体不兼容，业务可继续开发但不宣称模型可用。
- [x] 按完整 Unicode 码点编码，动态调整输入；空文本拒绝，超长资源失败返回错误。样件覆盖 1、40、41、160、10000 码点及 emoji，核对实际输入长度而非只看结果。
- [x] 实现解释器隔离、公共／个人推理、训练取消和参数恢复；训练配置有合理性检查，不猜测固定个人参数数量。
- [x] 合成样件验证训练前个人／基础一致、训练后基础不变、重建解释器加载参数后结果一致，错误分数不落库。
- [ ] 执行 `./gradlew.bat :app:compileDebugKotlin`；运行样件的设备结果放忽略目录，未有设备则列明未验。

## 任务 2：样本、人工纠正和预测来源

文件：新增 `data/db/PersonalLearningDatabase.kt`、`domain/spam/learning/PersonalLearningRepository.kt`、`data/repository/PersonalLearningRepositoryImpl.kt`；修改 `data/db/SpamDatabase.kt`、`data/repository/SpamRepositoryImpl.kt` 和 `di/AppModule.kt`。

实际接口：`LearningMessageKey(sourceType, sourceId, fingerprint)`；仓库 `record(key, text, label): Long`、`deleteSample(id)`、`clear()`、`unlinkMessage(key)`、`snapshot(): LearningSnapshot`、`observeState(): Flow<LearningState>`。代次使用单调 `Long`，没有独立 `LearningRevision` 类型；模型标识、个人代次和最终来源写入镜像判定字段，旧预测由迁移器导入，不作为训练标签。

- [x] Room 创建独立学习库，按正文摘要去重但核对正文以防摘要碰撞；标签更改更新所有关联纠正和样本代次。
- 当前存储与迁移按后续[主库合并方案](2026-11-05-main-database-consolidation.md)维护；个人学习库仍独立，旧 0/1 不转换训练标签，失效个人预测不作为当前结果。
- [x] 实现删样本／清空原子撤销纠正并失效参数，删原短信仅取消关联；无样本恢复基础预测。
- [ ] 用合成消息检查 ID 复用、共享正文冲突、删除影响数量、旧库升级保留规则及进程恢复。
- 独立学习库已接入生产判定；编译与设备验证按当前改动执行，不再保留准备阶段的暂不接入要求。

## 任务 3：开关与串行学习协调

文件：修改 `domain/settings/AppSettingsRepository.kt`、`data/repository/AppSettingsRepositoryImpl.kt`；新增 `domain/spam/learning/PersonalLearningCoordinator.kt`、`worker/PersonalLearningWorker.kt`、`worker/PersonalLearningScheduler.kt`。

接口：设置 `personalLearningEnabled=false`，不再提供自动脱敏设置；协调器 `requestLearning()`、`setEnabled(Boolean)`、`invalidatePersonalParameters(revision)`，输出排队／运行／成功／失败 StateFlow。模型、样本和设置代次构成提交条件。

- [x] 持久设置与任务状态，只在明确开启且有有效样本时训练；Worker 仅携带 ID／代次，不携带正文。
- [x] 唯一任务串行合并，训练前读一致样本快照，完成时比较全部代次；旧候选不能覆盖新标签或关闭状态。
- [x] 参数候选写入后校验并原子提交；关闭不删除数据，但停止采用个人结果；删样本后回基础直至新参数可用。
- [ ] 检查快速重复操作、取消、进程死亡和失败重试；重启恢复不能在关闭状态偷偷训练。
- [ ] 编译，记录样件训练时延及线程行为；避免在主线程和收信接收器内训练。

## 任务 4：资源 v2、迁移与原子切换

实现文件：`domain/hub/HubModels.kt`、`data/source/PixelTextHubClient.kt` 及其资源 DTO、`data/resource/HubResourceStore.kt`、`data/repository/HubResourceRepository.kt`、`worker/ResourceUpdateCheckWorker.kt`。组合存储直接由 `HubResourceStore` 实现，没有独立 `SpamModelBundleStore.kt`。

实际接口：v2 manifest 请求携带当前 versionCode 和 modelContractVersion=2；模型 DTO 包含五个能力字段及 model／vocab／contract 三个文件描述。`HubResourceStore.stageModelBundle(...)` 准备不可变目录，`PersonalLearningCoordinator.activateBundle(candidate, commit)` 完成候选学习和代次复核后调用 `activateModelBundle(candidate)` 原子更新指针；启动读取 `currentModelBundle()`，没有单独的 `recover()` 接口，也不单独替换词表。

- 已实现 DTO 和检查／下载／激活兼容判断，未知协议、过高最低版本及缺失能力拒绝；无兼容资源保留当前资源并展示明确状态。
- 已实现三个文件的大小、哈希及运行签名／词表配对校验，候选目录中准备整套资源；有学习样本且开关开时重建个人参数，成功再切换。这里的签名指 TFLite 调用签名，不表示资源有数字签名验签。
- 已实现关闭期间更新基础组合并使旧个人参数失效，不训练；重新开启重建，与任务 3 共用提交代次。
- 当前内置全文资源以 v2 组合读取，旧下载模型目录不作为主模型；模型变化安排无副作用历史重算。以上为源码实现状态，后面的故障与设备验证仍需独立证据。
- [ ] 用本地模拟响应检查 v2 不可用、未知契约、下载中断、重建失败、每个切换阶段崩溃；确保恢复最后完整组合。
- [ ] 编译及资源样件验证，外部服务暂未支持时不退回不兼容模型。

## 任务 5：统一判定与历史刷新

实现文件：`domain/spam/SpamRepository.kt`、`data/repository/SpamRepositoryImpl.kt`、`worker/SpamDetectionWorker.kt`、`worker/HistoricalSpamScanWorker.kt`、`data/repository/initialization/InitializationSpamScanner.kt`、`viewmodel/ConversationDetailViewModel.kt`。列表现由 `data/db/mirror/MirrorConversationQuery.kt` 按持久判定过滤，原 `LocalConversationSpamFilter.kt` 已移除；后台批处理见 `data/repository/MirrorSpamScanner.kt`。

实际判定由 `MessageSpamDecision.kt`、`SpamRepository` 及镜像骚扰 DAO 协作，分别保存模型评分、模型身份／代次、关键词、放行及人工纠正，再持久计算最终 `isSpam`。展示直接读取该字段；系统副作用前仍复核当前决定。人工标签写入任务 2 仓库，不伪造模型 0/1。

- [x] 集中实现优先级及模型快照选择，保持白名单最终锁与消息恢复保护的既有语义。
- [x] 新短信最终自动处理前核对完整决策代次；规则或标签变化时重算，不能拿过期结果执行删除。
- [x] 学习、开关和模型变化使预测缓存失效并持久排队重算；仅变更展示／统计，不重放历史副作用。
- [x] 核对已识别 ID 快速跳过逻辑：旧版本／失效代次不能永远跳过重算。
- [ ] 合成白名单、关键词、人工纠正、个人预测组合检查消息页、列表、未读数、Smartspacer 一致性；编译和 Lint。

## 任务 6：学习入口、管理与数据排除

文件：修改 `ui/screen/ConversationDetailScreen.kt`、`viewmodel/ConversationDetailViewModel.kt`、`ui/screen/SettingsScreen.kt`、`ui/screen/settings/SettingsPage.kt`、`viewmodel/SettingsViewModel.kt`、`ui/AppNavigation.kt`；新增 `ui/screen/PersonalLearningScreen.kt`、`viewmodel/PersonalLearningViewModel.kt`；核对 `data/backup/` 和根目录 `app/src/main/res/xml/backup_rules.xml`、`data_extraction_rules.xml`。

- [x] 原菜单位置替换入口，引导开关后保留当前短信；选择标签时说明规则冲突，确认后记录样本并启动学习。
- [x] 设置页展示开关、状态、数量、删除单条和清空；删除共享样本说明影响消息数量，关闭后明确数据保留。
- [x] 首次开启说明独立保存、删除短信不忘记及不备份；所有提示只讲用户行为，不展示私有实现。
- [x] 排除学习库、参数及临时文件的系统／手动备份与迁移；恢复设置不能意外开启二次学习或导入参数残留。
- [ ] 检查亮暗色、返回导航、旋转、进程恢复及无文本消息，编译并用 Mock／真机走通。

## 任务 7：手动辅助脱敏与确认上传

文件：复用 `domain/sample/SampleDesensitizer.kt`、`SampleDesensitizationAssistant.kt`；修改 `viewmodel/SampleSubmissionViewModel.kt`、`ui/screen/SampleSubmissionScreen.kt`、`domain/hub/SampleReportModels.kt`、`data/source/SampleReportDtos.kt`、`data/repository/SampleReportLocalStore.kt`、`SampleSubmissionRepository.kt` 和导航。

接口：通过 `SampleDesensitizationAssistant` 选择片段、预览并由用户应用替换，不使用自动脱敏接口；`SampleReportRequest.learningFeedback` 为可选对象，保存确认时的基础版本、个人代次和原始标签。不得重新读取运行状态生成重试负载。

- [x] 草稿初始化保留正文及来源提供的发送方；历史学习记录只带保存的正文和最新标签。用户主动打开辅助脱敏、选择片段并应用结果，不自动替换敏感内容。
- 学习与贡献确认独立；辅助脱敏只修改草稿，上传仍须明确确认，上传编辑不回写学习数据。
- [ ] 确认才冻结负载／请求 ID；取消无网络请求，未知结果恢复不改正文或元数据；旧待确认负载仍可恢复。
- [ ] 超出上报限制明确保留本地学习、不自动裁剪；长文本展示与提交错误可恢复。
- [ ] 在本地模拟端点核对实际请求及请求次数，不向真实端点发送测试短信；编译与界面验证。

## 任务 8：客户端独立验收与文档

文件：更新 `README.md`、`PRIVACY.md` 的客户端事实，必要时补 `docs/development/` 的公开运行契约；正式内置资源在明确通过验证后更新 `app/src/main/assets/`。临时证据放忽略的 `docs/.local/`。

- [x] 执行 `./gradlew.bat :app:compileDebugKotlin :app:lintDebug :app:assembleDebug`，顺序使用同一构建目录；不运行测试任务。
- [ ] 真机覆盖五项重点验收、正式运行库及 R8；必要时执行 Release 构建，注明实际签名情况。
- [ ] 记录质量样件结果、时延、峰值内存和包体增量；没有设备或不达标时明确阻塞发布的具体项。
- [ ] 检查差异、公开边界、引用和产物清单；只交付本仓库变更，不顺手修改外部工程或部署发布。

任务顺序：1 → 2 → 3 → 4 → 5 → 6 → 7 → 8。可执行契约样件是模型运行验收的外部输入，不是跨仓修改许可。
