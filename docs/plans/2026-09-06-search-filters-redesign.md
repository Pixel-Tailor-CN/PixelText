# 检索页面与筛选重设计实施计划

**目标：** 在完整消息镜像上实现号码、SIM、类型、日期、未读五项交集筛选，关键词仅检索 SMS 正文及 MMS 主题、全部纯文本和 HTML 可读文字。

**架构：** Room 参数化可观察查询负责筛选，MessageMirrorRepository 暴露轻量搜索投影，MessageRepository 补充骚扰状态。应用级 MMS 派生任务负责专用搜索正文；搜索 ViewModel 负责查询取消与条件状态，Compose 子页和弹层负责可保存草稿。

**技术：** Kotlin、Room、Coroutines/Flow、Compose、Material 3、Koin、java.time。

**设计依据：** [已确认设计](2026-09-06-search-filters-redesign-design.md)。本计划不重复请求已经确认的产品和架构决策；由原会话担任 Herdr 管理员，获批执行者逐步实现，独立审查者逐步审查，无自动 Git 提交。

## 实施与验证状态（2026-09-13）

任务 1—6 的代码实现、集成编译及独立审查已完成，工作分支为 `feat/search-filters-redesign`，未提交 Git。以下清单保留原始逐项验收定义，当前已验与待验边界以本节为准，不将代码完成等同于全部运行时场景通过。

- 已实现：领域筛选、共享号码归一化、显式 v2→v3 Migration 与 Room 导出 schema、MMS 专用搜索正文、参数化可观察 SQL、批量骚扰标记、查询取消代次、号码子页与五项筛选、消息点击路由。
- 已通过：`./gradlew.bat :app:compileDebugKotlin`、`:app:lintDebug`、`:app:assembleDebug`、`git diff --check`。未新增或运行单元测试。
- 模拟器：经用户批准，在唯一已连接模拟器保留数据安装 Debug APK，使用用户确认的既有合成测试短信；执行与独立审查分别覆盖搜索、高亮、普通会话跳转及返回、类型筛选、号码子页和横屏状态保持。不将有限交互覆盖扩大为全部状态恢复场景通过。
- 仍待验证：含历史附件的 v2→v3 就地升级运行时与字段/引用保留；万级合成数据查询时延、取消响应及内存；MMS `id=-1`、无有效 thread、骚扰内容视图的运行时导航；无联系人权限系统选号（本轮未实际调起选择器）；真实双卡、运营商 SMS/MMS 链路及真机读屏/键盘兼容性。
- 原始运行证据、返修与流程偏差记录保存于已忽略的本地协同目录，不纳入正式文档或 Git。阶段审查通过不替代上述待验项。

## 全局约束

- 不增加网络请求、权限、FTS、分页或结果上限，不运行或新增单元测试。
- v2→v3 显式迁移，保留原始消息字段与附件引用；v1 用户可通过既有 v1→v2 连续升级。
- 电话号码仅使用数字包含语义；联系人选择不读取整张通讯录，不请求 READ_CONTACTS。
- SIM 全选提交后归一化为空集合，类型全选仍保留展示状态，二者查询均不限。
- SMS/MMS 无效 thread 使用独立详情，有效 thread 进入会话定位，包括 MMS ID=-1。
- 文本派生未就绪不得宣告确定空结果；图片/视频等复制不阻塞文字检索。
- 全部代码路径以下均相对 `app/src/main/java/vip/mystery0/pixel/text/`，schema 路径相对仓库根目录。

## 接口与执行顺序

任务 1→2→3→4→5→6 顺序执行。通过真实 Herdr CLI 和角色窗格调度，默认复用一个执行者和一个独立审查者；同一实现目录只允许执行者写入业务代码，Gradle 任务顺序运行。管理员只维护计划、契约和协同记录，不代写实现。

### Herdr 接管与授权检查点

- 用户已批准将准备阶段发现的既有未提交搜索实现纳入本次接管；保留原始差异，不将其视为完成或已审查。用户已批准后续在当前目录新建任务分支，不直接在 main 实施。
- 模型与思考档位须分别取得执行者、审查者授权，随后确认 CLI、提供方与备用路线；未批准的备用路线一律失败停止。通过真实身份、认证、模型及集成的有界预检后才派业务任务。
- 启动前在已忽略的 `docs/.local/` 下建立独立运行记录、Git 基线及含未跟踪文件的既有差异快照。审查者先收到契约并确认角色，再派执行者接管核对；不能把终端写入成功当成业务 ACK。
- 每个任务派发前建立独立契约，锁定设计/计划版本、run/task/attempt、绝对 cwd、允许读取和修改的路径、依赖、禁止事项、验证命令及时限、冻结差异与证据路径。以下各任务的文件清单是范围依据；发现额外写入需求先升级管理员，不自由扩展。
- 每步遵循“派单 → 接单 → 实现及证据 → 独立审查 → 管理员接受 → 下一步”。任务 1/2 可按下述约定以源码、迁移及索引路径证据进行阶段审查，编译集成验收延后至任务 3；延期验证必须明确记录，不宣称阶段代码已通过编译。
- 保留已确认的不新增、不运行单元测试约束，以编译、Lint、合成数据及适用真机验证替代；无设备或性能证据不足时逐项标记未验证，不能以编译成功替代验收。
- 任务 1 的接管读查范围还包括既有 `MirrorSearchMigration.kt`、`domain/model/search/` 和 `MmsContentModel.kt` 差异；按职责归入对应步骤，保留来源。schema 需 Room 实际导出，若编译依赖使导出延至任务 3，必须记录待验项。
- 契约内默认最多两轮返修，每轮更新 attempt 并重新冻结差异和独立审查；架构、权限、依赖、费用或验收范围变化，以及身份、认证、通信不确定时暂停相关步骤并升级。
- 管理员有界等待后核对业务回执、结果文件、待处理消息和真实身份，不盲目重发或重启。全部步骤、集成证据和最终审查核验后才接受交付；提交、推送、发布与部署另需明确授权。

为避免 UI、仓库和索引任务各自定义状态，接口统一如下（新模型放在 `domain/model/search/`）：

```kotlin
data class MessageSearchRequest(
    val query: String,
    val filter: MessageSearchFilter,
    val beforeTimestampExclusive: Long?,
)

data class MessageSearchBatch(
    val messages: List<MessageModel>,
    val incomplete: Boolean,
)

// MessageMirrorRepository：只提供镜像投影与文本就绪状态，不访问骚扰库。
fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch>

// MessageRepository：组合镜像结果与批量骚扰判定，保留 incomplete。
fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch>
```

`MessageModel` 在搜索中仅作为轻量兼容结果：不加载原始快照、附件字节或业务卡片解析；content 装入实际命中正文/主题以供高亮。请求中的 query 是实际执行的关键词，纯空白视为无关键词，非空内容不隐式拆词。初始同步状态尚未读到时采用保守 incomplete=true，不把初始空列表当作已完成结果。

DAO 生成结果查询和未就绪统计查询时复用同一组受控筛选子句；后者不加关键词条件，否则缺失索引的记录永远不会被计入缺口。两者在一次 Room 事务读取中形成一个批次，避免结果与完整性取自不同数据库瞬间。骚扰观察只重新补充批量分类，不重新解析 MMS。

**阶段检查点：** 数据层完成后执行首次编译；UI 接入后编译与 Lint。实施过程中记录实际完成步骤，不把仅写入计划的步骤勾选为已完成。

## 任务 1：领域筛选与数据库迁移

**文件：** 新增 `domain/model/search/MessageSearchFilter.kt`、`domain/model/search/SearchPhoneNumbers.kt`；修改 `domain/repository/MessageRepository.kt`、`data/db/mirror/MirrorEntities.kt`、`data/db/mirror/MessageMirrorDatabase.kt`、`data/source/mirror/TelephonyMirrorSource.kt`；新增 `app/schemas/vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase/3.json`（由 Room 导出）。

- [ ] 定义筛选集合、日期枚举及号码信息，提供 `isActive`、有效类型集合和 SIM 归一化，集中处理不限语义。任务 1 新领域模型可与旧仓库筛选模型短暂并存；任务 3/4 更新调用链时删除旧模型，最终不留下双套接口。
- [ ] 日期枚举按 ZonedDateTime 日历减法生成毫秒排他上界；一次查询只计算一次。
- [ ] 定义共享 `digits` 与 `queryAliases`：格式符清除、显式 +86/0086 或完整 86 国内手机号生成非空别名，短片段不裁剪。
- [ ] 新增 SMS normalizedAddress；MMS 文本派生表增加 searchBody、searchReady、sourceRevision，原 searchableText/summary 消费者保持不变。
- [ ] Migration 使用键集分批读取已有原始号码，共享 Kotlin 归一化，参数化回填；索引新增字段默认未就绪，等待版本重建，不依赖 Provider 权限。
- [ ] 后续镜像入库统一写入同一号码形态；不动原始字段或附件。

核心约定：

```kotlin
// 字段名是任务间契约，界面持有日期选项，数据层接收固定截止值。
data class MessageSearchFilter(
    val unreadOnly: Boolean = false,
    val simSubIds: Set<Int> = emptySet(),
    val transports: Set<MessageTransport> = emptySet(),
    val phoneNumber: String? = null,
    val phoneDisplayName: String? = null,
    val date: SearchDate = SearchDate.ANY,
)
```

**验收：** 核对 v1→v2→v3 迁移链与 schema；对格式符、国家码、短号及无数字输入进行代码路径检查；任务 3 后统一编译。

## 任务 2：限定 MMS 搜索正文及完整性

**文件：** 修改 `domain/model/mms/MmsContentModel.kt`、`data/repository/mms/MmsContentRepositoryImpl.kt`、`data/repository/mms/MmsTextIndex.kt`。

- [ ] 内容模型增加独立 searchBody 与 searchReady，不更改旧正文展示选择。
- [ ] 从全部纯文本与 HTML 可读文字按 part 顺序构建正文，不从 selection.parts 构建，不包含名片/日历/SMIL/文件名；复用既有 HTML 安全处理，不查询原始 HTML。
- [ ] 文本读取失败、预算超限、HTML 处理问题标记未完整，允许已读部分命中；待下载只搜索主题，不等待下载。
- [ ] 升级派生版本并写入 sourceRevision；写入前核对指纹，附件变化继续沿用已有派生行失效机制。
- [ ] 解析单条失败不能停止全部后续索引；取消异常必须继续抛出，日志不得包含正文或地址。

**验收：** 查阅全部 part 与展示子集的差别；确认旧版索引不能参与新搜索；确认图片复制状态不决定 searchReady。

## 任务 3：原生 SQL 检索与仓库接口

**文件：** 新增 `data/db/mirror/MirrorSearchQuery.kt`、`data/db/mirror/MirrorSearchDao.kt`；修改 `MessageMirrorDatabase.kt`、`domain/repository/MessageMirrorRepository.kt`、`data/repository/mirror/MessageMirrorRepositoryImpl.kt`、`data/repository/MessageRepositoryImpl.kt`。

- [ ] 使用轻量搜索行而非全套原始快照/附件关系，绑定 WHERE 参数并稳定排序；不先载入全部消息再过滤。
- [ ] SMS 只匹配 body；MMS 匹配解码主题回退原主题和有效 searchBody，subject 命中应能显示主题片段。
- [ ] 号码 SMS 使用规范化地址，MMS 有外部 FROM 时只匹配 FROM，否则匹配 TO/CC/BCC，排除 insert-address-token，使用 EXISTS 防重复。
- [ ] 关键词按字面量转义 LIKE，日期 timestamp < cutoff，类型/SIM 用绑定 IN 子句；未读保持现有未知 read 被视为非已读的兼容语义。
- [ ] 可观察查询显式列举关联表；提供同一筛选下、忽略关键词的未就绪 MMS 数，避免查询可能命中的消息被索引缺失隐藏。
- [ ] MessageRepository 组合镜像结果及骚扰库观察，批量读取骚扰 ID，用与详情一致的阈值标记结果；不进行分类或解析。搜索接口在任务 3 改为 MessageSearchRequest/MessageSearchBatch；同期调整 SearchViewModel 的最小适配调用，保证阶段编译通过，再在任务 4 完整替换调度和筛选 API。
- [ ] 合并消息源同步和搜索文本缺口，向页面暴露不完整状态；根源首次成功前、结构缺失或错误不能确定全空，辅助集合及普通后台扫描不阻塞。

**验收命令：** `./gradlew :app:compileDebugKotlin`。核对 SQL 投影字段与 Room 实际生成结构；不运行 test 任务。

## 任务 4：搜索请求与状态生命周期

**文件：** 重写 `ui/message/search/SearchViewModel.kt`；按需修改 `di/AppModule.kt`。

- [ ] 保留搜索目的地一个 ViewModel，暴露关键词、筛选及状态 StateFlow。
- [ ] updateQuery 立即取消旧 Job、递增请求代次，最新 Job 内等待 300ms；筛选提交直接使用当前关键词并取消待防抖任务。
- [ ] 新查询进入 Loading，空关键词无有效筛选直接 Idle；成功携带实际关键词，取消不发布 Error，错误使用固定中文文案。
- [ ] 每次实际查询捕获固定日期边界，数据库更新不重新计算；提供错误重试入口。
- [ ] SIM 刷新移除失效 ID、全覆盖归一化不限；类型保留用户实际全选状态。

核心调度顺序：

```kotlin
// 新事件同步使前一代失效，而不是对关键词流先 debounce。
searchJob?.cancel()
val generation = ++requestGeneration
searchJob = viewModelScope.launch {
    if (debounce) delay(300)
    // 捕获 query/filter/cutoff，再收集 repository；发布前核验 generation。
}
```

**验收：** 检查取消位置在 delay 前，筛选没有从 debouncedQuery 取旧值，错误/成功不吞 CancellationException。

## 任务 5：号码页、筛选栏、弹层和列表

**文件：** 重写 `SearchScreen.kt`；新增 `SearchPhoneScreen.kt`、`SearchFilterBar.kt`、`SearchFilterSheets.kt`、`data/source/PickedPhoneSource.kt`；修改 `SearchResultList.kt`、`SearchResultItem.kt`、`di/AppModule.kt`。

- [ ] 搜索框仅返回/关键词/清空，下方五项固定顺序；前四个有箭头，未读直接切换。
- [ ] 子页面采用共享 ViewModel 和可保存页状态；号码草稿电话键盘、结果项应用、清除、联系人选择；系统返回优先放弃号码草稿。
- [ ] ACTION_PICK + Phone.CONTENT_TYPE，仅读取返回 URI NUMBER/DISPLAY_NAME，在 IO 调度读取；取消静默保留草稿，失败中文提示，无权限申请。
- [ ] SIM/类型弹层 rememberSaveable 草稿，重置不提交，应用提交，取消丢弃；日期立即提交；刷新 SIM 于恢复前台及打开弹层。
- [ ] 列表 LazyListState 提升到父搜索页，子页隐藏与详情返回不丢位置，新请求回顶部。
- [ ] 实际命中文本负责摘要与高亮；空结果区分完整与不完整，错误可重试；长标签省略及读屏描述、IME 避让和主题色沿用 Material 3。

**验收：** 编译；手工检查零选/全选、重置取消、号码返回、选号码失败、旋转和详情返回；无设备项明确未验证。

## 任务 6：导航、复核与交付

**文件：** 修改 `ui/AppNavigation.kt`；核对 `ConversationDetailViewModel.kt`、`ConversationDetailScreen.kt`，不重复改造已经可空的 messageId 参数。

- [ ] 移除所有 MMS 强制进入独立详情的分支；仅无效 thread 使用独立详情，其余传递真实 ID 及正确骚扰视图。
- [ ] 自检每项设计对应实现，尤其 MMS ID=-1、主题/非首 part 摘要、未就绪空状态、查询不限与 UI 草稿区别。
- [ ] 顺序运行 `./gradlew :app:compileDebugKotlin :app:lintDebug`，失败定位相关改动后复验。
- [ ] `git diff --check`，检查修改清单与 schema；不纳入日志、敏感数据或构建产物。
- [ ] 汇报实际验证、真机待验和性能证据；无真机不得声明系统链路通过。
