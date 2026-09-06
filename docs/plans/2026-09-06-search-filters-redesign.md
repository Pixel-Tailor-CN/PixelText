# 检索页面筛选重设计实施计划

> 执行者：先完整阅读配套设计，使用 executing-plans 技能逐项实施，以复选框记录进度。遵守仓库 AGENTS.md；本文件不授权运行单元测试。

**目标：** 以 Gmail 风格筛选栏重做消息检索，支持号码片段、SIM 多选、消息类型、早于日期及未读筛选。

**架构：** 保留 `searchMessages()` 和单一消息结果列表。ViewModel 管理已应用状态，号码页与弹层管理草稿；仓库统一筛选语义，Telephony 处理系统查询，号码选择只读取系统临时授权 URI。

**技术栈：** Kotlin、Compose、Material 3、Flow、Koin、Telephony、Activity Result、java.time。

**设计：** [检索页面与筛选交互重新设计](2026-09-06-search-filters-redesign-design.md)。

## 全局约束

- 中文文档与注释；英文小写开头日志，不打印短信、联系人或输入号码。
- Android 12+、JVM 21；不新增依赖、权限、数据库、网络请求或 XML layout。
- 不新增/运行单元测试，使用编译、Lint、手动与真机验证。
- 不恢复旧方案的会话分区、姓名联想、聚合结果模型及 100 条上限。
- 当前 Gradle 版本配置是用户未提交修改，不能覆盖或纳入本任务提交。
- 未经授权不发布新 PR；实施开始前确认当前设计分支与工作区状态。

## 任务 1：筛选模型与号码/日期语义

**文件：** 修改 `app/src/main/java/vip/mystery0/pixel/text/domain/repository/MessageRepository.kt`，新增同目录 `MessageSearchFilter.kt`；修改 `data/source/ContactDataSource.kt`（以下路径均相对上述应用包）。

**接口：** 保留 `searchMessages(query: String, filter: MessageSearchFilter): Flow<List<MessageModel>>`。

建议模型：

```kotlin
enum class SearchMessageType { SMS, MMS }
enum class SearchDateFilter { ANY, WEEK_AGO, MONTH_AGO, HALF_YEAR_AGO, YEAR_AGO }

data class MessageSearchFilter(
    val unreadOnly: Boolean = false,
    val simSubIds: Set<Int> = emptySet(),
    val messageTypes: Set<SearchMessageType> = emptySet(),
    val date: SearchDateFilter = SearchDateFilter.ANY,
    val senderNumber: String? = null,
    val senderDisplayName: String? = null,
) {
    fun isActive(): Boolean = unreadOnly || simSubIds.isNotEmpty() ||
        messageTypes.size == 1 || date != SearchDateFilter.ANY ||
        !senderNumber.isNullOrBlank()
}
```

- [ ] 将旧模型移至独立文件，查全调用点，替换 `simSubId`、`mmsOnly`、`contactAddress`，不修改与搜索无关的同名系统参数。
- [ ] SIM 全选在 UI 提交时依据当前卡集合归一化为空集合；类型集合保留选择状态，查询只在大小为 1 时生效。
- [ ] 添加 `SearchDateFilter.cutoffMillis(now: ZonedDateTime): Long?`，使用 `when` 分别返回 null、`now.minusDays(7)`、`minusMonths(1)`、`minusMonths(6)`、`minusYears(1)` 的 epoch millis。
- [ ] 在 ContactDataSource 添加 `matchesNumberFragment(fragment: String, candidateAddress: String): Boolean`，先检查有效数字非空，再生成非空号码别名，判断候选包含片段，禁止反向包含或纯文字转换成电话号码数字。
- [ ] 保留现有 `matchesAddress()` 的完整号码语义，避免影响旧调用者；不因片段搜索加载联系人缓存或检查联系人权限。
- [ ] 手动静态核对 `138`、`106`、`+86 138…`、`0086 138…`、空格及纯文字；核对日期跨月与闰日的定义。

## 任务 2：统一短信和彩信检索条件

**文件：** `data/repository/MessageRepositoryImpl.kt`、`data/source/TelephonyDataSource.kt`。

**接口：** 两个 Telephony 搜索方法均接受 `query: String, unreadOnly: Boolean, simSubIds: Set<Int>, beforeTimestampMillis: Long?`，返回原有 row 列表。

- [ ] 仓库开始查询时只捕获一次 `ZonedDateTime.now()`，用任务 1 方法计算 cutoff。
- [ ] 类型为空或包含两项查询两种消息；单项集合仅查询对应类型，避免无用 MMS part 扫描。
- [ ] SMS SQL 保留 BODY 查询，并组合 READ、`SUBSCRIPTION_ID IN (?, …)`、`DATE < ?`；所有用户值通过参数传入。
- [ ] MMS 主题 SQL 同样组合未读、SIM 和日期；MMS 秒级日期上界使用毫秒上界向上取整到秒，使严格小于条件与模型毫秒比较一致。
- [ ] MMS 文本 part 命中映射 row 后统一检查未读、集合包含和时间；更新旧 `matchesSearchFilters()`，禁止仅修主题入口。
- [ ] 两个 MMS 入口按 ID 去重后映射。仓库合并 SMS/MMS，调用号码片段匹配，按 timestamp 倒序返回；不要添加 `ADDRESS LIKE` 到正文的 OR 分支。
- [ ] 不添加早于号码筛选的 LIMIT；不引入旧方案的 take(100)。大结果集读取循环检查协程取消，耗时 ContentResolver 查询按可用重载接入 CancellationSignal，取消不能吞为普通空结果。
- [ ] 如查询文本的 `%`、`_` 沿用现有 LIKE 行为，本任务不扩展搜索语法；不要拼接号码和 SIM ID 进 SQL。
- [ ] 检查 SMS 与 MMS 在空关键词、单类型和多筛选下的查询路径，对照设计验证日期与去重。

## 任务 3：ViewModel 提交与取消

**文件：** `ui/message/search/SearchViewModel.kt`。

**接口：** 提供 `setSenderFilter(number: String, displayName: String?)`、`clearSenderFilter()`、`setSimFilter(subIds: Set<Int>)`、`setMessageTypes(types: Set<SearchMessageType>)`、`setDateFilter(date: SearchDateFilter)`、保留 `toggleUnreadFilter()` 与 `updateQuery()`。

- [ ] 使用新模型，删除分散的旧 `isActive()`，由模型统一判断空条件；Success 仍持有 `List<MessageModel>`。
- [ ] 将请求流改为最新请求替换旧请求。查询输入与筛选变更统一携带请求版本，输入一变即使旧版本失效，300ms 后启动新关键词请求；筛选提交不等待防抖。
- [ ] 使用 `flatMapLatest` 或持有可取消 Job，并在发布 Loading/Success/Error 前确认版本仍有效；取消异常继续传播。
- [ ] 号码提交清理首尾空白，无有效数字不提交；清除号码同时清除显示名。
- [ ] SIM/类型 setter 一次性更新集合，不能逐项调用导致中间查询。保持原始关键词清空与关闭搜索行为。
- [ ] 静态核对连续输入与筛选变化下旧结果不能覆盖新请求，空有效条件返回 Idle。

## 任务 4：号码输入页与无通讯录权限选号

**文件：** 新增 `ui/message/search/SearchSenderScreen.kt`、`data/source/PickedPhoneNumberReader.kt`；修改 `ui/message/search/SearchScreen.kt`。需要注入时在 `di/AppModule.kt` 注册 reader。

**接口：** `data class PickedPhoneNumber(val number: String, val displayName: String?)`；reader 的 `suspend fun read(uri: Uri): PickedPhoneNumber?` 在 IO 上只查返回 URI。

系统选择器核心请求：

```kotlin
Intent(Intent.ACTION_PICK).apply {
    type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
}
```

- [ ] 号码页接收当前号码和显示名，回调 `onApply: (String, String?) -> Unit`、`onClear: () -> Unit`、`onBack: () -> Unit`，使用 rememberSaveable 保留号码草稿。
- [ ] 渲染电话键盘输入框、动态“查看与 … 相关的会话”行、“从联系人选择”和已有条件的清除入口；仅有效数字片段可应用。
- [ ] Activity Result 启动上面的 Intent。reader 读取返回 URI 的 Phone.NUMBER/Phone.DISPLAY_NAME，不读取 Contacts._ID 再查询 Phone.CONTENT_URI。
- [ ] 无活动处理、SecurityException、空 URI/号码、查询失败只返回失败并提示手动输入；取消不提示错误、不改变草稿。
- [ ] 删除 SearchScreen 原有选号专用联系人权限对话框、READ_CONTACTS launcher、默认短信角色 launcher 和整表号码解析函数。
- [ ] 在搜索目的地内部切换主页面与号码页面，使用同一 ViewModel；系统返回由号码页先消费。应用一次性更新、关闭号码页、隐藏键盘，不清空主关键词。
- [ ] 人工核对项目仍需的其他联系人权限流程不受影响，搜索页没有新增权限申请。

## 任务 5：五项筛选栏与弹层

**文件：** 新增 `ui/message/search/SearchFilterBar.kt`、`ui/message/search/SearchFilterSheets.kt`；修改 `SearchScreen.kt`、`SearchResultList.kt`。

**接口：** 筛选栏消费 MessageSearchFilter 和 List<SimInfo>，通过各项点击回调打开页面/弹层；弹层通过已选集合与 `onApply` 回调提交。

- [ ] 按固定顺序渲染五项 FilterChip，前四项 ArrowDropDown，未读直接切换；字号、间距、颜色沿用 MaterialTheme，长名称单行省略。
- [ ] SIM 移除 `.take(2)` 和 firstSim/secondSim 逻辑；恢复前台、打开弹层时刷新列表，交集修剪失效选择并归一化全选。
- [ ] SIM 和类型分别维护可保存的草稿。打开时从已应用值初始化，重置仅修改草稿，应用才提交，dismiss 丢弃；不限的 SIM 重新打开时统一全不选。
- [ ] 无 SIM 信息显示明确空态并允许应用空集合清除条件，不申请电话状态权限；同名卡显示卡槽辅助文本。
- [ ] 日期按设计枚举显示 RadioButton，整行可选、单选即回调并 dismiss；显示“任意时间”对应 ANY。
- [ ] 去掉顶部旧联系人图标入口，用发件人筛选项替代；修改引导和空结果文案，保持单一消息 LazyColumn 及 stableKey。
- [ ] 多选行用整行 toggleable，复选框避免重复触发；单选行用 selectable；配置中文无障碍标签和选中态语义。
- [ ] 手动检查重置后取消、应用后重开、旋转、长卡名、键盘/导航栏避让以及暗色模式。

## 任务 6：所有消息 ID 的导航定位

**文件：** `ui/AppNavigation.kt`；核对 `ui/screen/ConversationDetailScreen.kt`、`viewmodel/ConversationDetailViewModel.kt`。

- [ ] 搜索 onResultClick 传入 `messageId = message.id`，保留现有骚扰阈值/contentFilter 选择。
- [ ] 路由 messageId 参数改为 `NavType.StringType`、`nullable = true`、`defaultValue = null`；构造路由时无消息 ID 就不附加该参数，有值则使用真实 Long 字符串。
- [ ] 路由解析使用 `getString("messageId")?.toLongOrNull()`，移除针对消息 ID 的 `> 0` 与 `-1` 缺省判断，不修改 threadId 校验或真正的 SMS-only 分支。
- [ ] 详情页继续接受 `Long?`，loadUntilMessage 继续使用相等比较；核对其他会话和验证码入口仍能跳转。
- [ ] 手动覆盖正数 SMS、负数 MMS、`-1` MMS、无目标普通会话和已删除消息提示。

## 任务 7：整体验证与交付

- [ ] 执行 `./gradlew :app:compileDebugKotlin`，必须通过；如用户本地 Gradle 配置导致环境问题，记录真实错误而非回滚配置。
- [ ] 执行 `./gradlew :app:lintDebug`，区分新增问题与既有问题，不运行 test 类任务。
- [ ] 静态搜索确认搜索流程无 READ_CONTACTS 请求，无聚合会话结果，无单卡旧字段，无丢弃负数消息 ID 的逻辑。
- [ ] 真机无联系人权限选择多号码联系人、取消、手动片段输入，确认无权限弹窗；不可用真机时明确标记未验证，不能勾选为完成。
- [ ] 按设计验证所有筛选组合与日期边界，尤其 MMS 主题/part、全选不限、失效 SIM、旧联系人发件人的较早消息召回。
- [ ] 验证无条件引导、条件清除、快速输入、返回与旋转、暗色和消息定位；记录构建结果与仍需真机验证项。
- [ ] `git diff --check`，只提交本任务文件，不夹带现有 Gradle 修改与构建产物。
