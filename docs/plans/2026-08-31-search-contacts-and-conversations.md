# 搜索支持联系人与会话命中 Implementation Plan

> **For agentic workers:** 先完整阅读 `docs/plans/2026-08-31-search-contacts-and-conversations-design.md`，再按本计划逐项实现。步骤使用 checkbox (`- [ ]`) 语法跟踪。

**Goal:** 让搜索框输入联系人姓名或号码时优先返回会话，输入关键词时继续返回短信，并在结果中展示显示名。

**Architecture:** 搜索页继续使用现有入口、筛选 chip 和联系人 picker。仓库层新增聚合搜索 `search()`，会话命中走会话缓存 + 联系人/服务号显示名匹配，短信命中继续走 Telephony 正文检索。UI 将 `SearchUiState.Success` 改为同时承载会话列表和短信列表，分区渲染。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Coroutines/Flow、Koin、Android Telephony ContentProvider、Room 会话缓存

## Global Constraints

- 使用中文回复，生成的代码注释和文档使用中文，日志打印使用英文。
- UI 必须使用 Jetpack Compose，不新增 XML layout 文件。
- 优先沿用现有架构、命名和包边界。
- 不引入第三方搜索依赖，不引入拼音或在线搜索。
- 本次不实现收藏 / 加星。
- 本项目不做单元测试；除非用户明确要求，否则不要新增测试代码或运行 `test` 任务。
- 验证优先使用编译、Lint 和手动检查。
- 实现必须符合设计文档中的搜索语义：姓名命中只进会话分区，正文命中才进短信分区。

## 设计约束摘要

- 会话命中字段：联系人显示名、号码归一化 key、服务号资料名。
- 短信命中字段：短信正文、彩信文本、彩信主题。不要把 `ADDRESS LIKE` 并入短信查询。
- 会话最多 20 条；短信最多 100 条。短信 100 是合并 SMS/MMS 并完成筛选之后的总上限。
- 会话分区排除归档；开启骚扰隔离时排除骚扰会话。
- 无联系人权限时不按姓名命中，但号码和服务号名仍可命中。
- 保留现有联系人 picker、未读 / SIM / 彩信筛选。
- 彩信 `message.id` 为负数；路由 sentinel 只使用 `-1`。

---

### Task 1: 扩展联系人匹配与搜索结果模型

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/source/ContactDataSource.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/repository/MessageRepository.kt`
- Create or colocate: `SearchResults` 数据模型

**Interfaces:**
- Consumes:
  - 现有 `contactLookupKeys()` / `matchesAddress()`
  - 现有 `ConversationModel`、`MessageModel`、`MessageSearchFilter`
- Produces:
  - `ContactDataSource.findAddressesByDisplayName(query: String): Set<String>`
  - `ContactDataSource.matchesQuery(address: String, displayName: String?, query: String): Boolean`
  - `data class SearchResults(conversations, messages)`
  - `MessageRepository.search(query, filter): Flow<SearchResults>`

- [ ] 在 `ContactDataSource` 中增加按显示名反向查找。对已加载缓存做忽略大小写的 `contains` 匹配，返回对应号码原始值和归一化 key 可用的地址集合。
- [ ] 增加统一的 `matchesQuery()`：姓名 contains query，或号码 lookup keys 与 query lookup keys 有包含关系。
- [ ] 无 `READ_CONTACTS` 时，姓名反查返回空集合，号码匹配仍可用。
- [ ] 定义 `SearchResults`，提供 `isEmpty`。
- [ ] 在 `MessageRepository` 中新增 `search()`，保留旧的 `searchMessages()` / `searchConversations()` 以免无关调用点大改。

### Task 2: 实现聚合搜索仓库逻辑

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/source/TelephonyDataSource.kt`

**Interfaces:**
- Consumes:
  - 会话缓存、归档库、骚扰仓库、设置仓库、联系人数据源、服务号资料仓库
  - `TelephonyDataSource.searchSmsMessages()` / `searchMmsMessages()`
- Produces:
  - `search()` 聚合结果

- [ ] 实现 `search()`。query 为空且无筛选时不要走这个方法的成功分支；由 ViewModel 继续映射到 `Idle`。
- [ ] query 非空时构建会话分区：复用与 `getAllConversations()` 相同的缓存、显示名补全、归档过滤和骚扰隔离。
- [ ] 会话过滤使用 `ContactDataSource.matchesQuery()`，并额外用服务号 `displayName` contains query。
- [ ] 将会话筛选叠加到会话分区：联系人 chip、未读、彩信。SIM 筛选不作用于会话分区。
- [ ] 会话结果按时间倒序，最多 20 条。
- [ ] 短信分区继续使用现有正文 / 主题检索 + 联系人号码过滤，不要因为姓名或号码命中而自动加入短信结果。
- [ ] 查询层可加安全 `LIMIT` 防万级扫表，但不要把产品 100 落在 `searchSmsMessages()` 和 `searchMmsMessages()` 各自的查询结果上。
- [ ] 产品 100 条上限必须在仓库层按这个顺序落地：合并 SMS + MMS → 联系人 / 未读 / SIM / 彩信筛选 → 按时间倒序 `take(100)`。
- [ ] 有联系人 chip 时，先过滤号码再截断。能把号码约束推进 Telephony 查询就推；否则过量取回后用 `matchesAddress()` 过滤。禁止先 `LIMIT 100` 再按联系人过滤。
- [ ] query 为空但筛选激活时，会话分区为空，只返回短信结果，保持旧的「仅联系人筛选列出短信」行为；这条路径同样要先筛选再截 100。
- [ ] 若会话缓存未 ready，先走与列表相同的同步，避免搜索不到列表里已有的会话。
- [ ] 日志只输出英文小写短语和关键计数，例如 `search query_len=2 conversations=3 messages=12`。

### Task 3: 更新 SearchViewModel 与导航回调

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchScreen.kt`
- Modify: 会话详情路由解析与定位逻辑，通常是 `AppNavigation.kt` 和 `ConversationDetailScreen` / `ConversationDetailViewModel`

**Interfaces:**
- Consumes: `MessageRepository.search()`
- Produces:
  - `SearchUiState.Success(SearchResults)`
  - `onConversationClick` / `onMessageClick`

- [ ] `SearchUiState.Success` 改为持有 `SearchResults`。
- [ ] ViewModel 改调 `repository.search(query, filter)`，保持 300ms debounce 和现有筛选 toggle API。
- [ ] `SearchScreen` 增加会话点击回调；短信点击回调保持。
- [ ] `AppNavigation` 中，会话点击进入 `conversationDetailRoute(threadId, address)`。
- [ ] 消息点击传入 `messageId = message.id`。骚扰分数阈值逻辑保持现有 `contentFilter` 判断。
- [ ] 修正 `conversationDetailRoute()`：只把 `-1` 当作缺省 sentinel，不要再用 `takeIf { it > 0L }` 丢掉负数彩信 ID。
- [ ] 修正详情页路由解析和定位逻辑，使 `targetMessageId != -1L` 的消息都能被定位，包括 MMS 负数 ID。
- [ ] 占位文案改为「搜索联系人或短信」。

### Task 4: 分区结果 UI 与显示名

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchConversationResultItem.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultList.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultItem.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchScreen.kt`

**Interfaces:**
- Consumes: `SearchResults`、`ConversationModel`、`MessageModel`
- Produces: 分区搜索结果列表

- [ ] 新增会话结果行：头像、显示名或号码、snippet、时间。视觉密度对齐会话列表，不要做成第二套短信行。
- [ ] `SearchResultList` 在 Success 中按「会话」「短信」分区渲染；某一区为空则不显示该区标题。
- [ ] Idle 文案改为「输入姓名、号码或关键词搜索」。
- [ ] 双边都空时文案改为「没有找到匹配的联系人或短信」。
- [ ] 若 query 含非数字字符、结果为空、且没有联系人权限，追加「授予联系人权限后可按姓名搜索」。权限状态由 Screen 传入，不要在 List 里直接读 Context 权限导致难以预览。
- [ ] `SearchResultItem` 增加 `displayName: String?` 参数，主标题优先显示名，其次号码。
- [ ] 短信显示名在仓库映射结果时补上，或在 Screen/List 层用现成联系人读取；优先在仓库聚合时写入，避免 UI 层再查一遍。如果 `MessageModel` 当前没有显示名字段，优先给 `MessageModel` 增加可空 `displayName`，不要在 UI 里临时塞 map。

### Task 5: 编译验证与手动回归

**Files:**
- No code changes required unless verification reveals issues.

- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期 `BUILD SUCCESSFUL`。
- [ ] 确认没有把 `ADDRESS LIKE` 加进短信正文查询。
- [ ] 确认搜索页不再只依赖 `searchMessages()`。
- [ ] 确认短信 100 条上限落在合并加筛选之后，而不是 SMS/MMS 各自 `LIMIT 100`。
- [ ] 确认彩信搜索结果点击能把负数 `messageId` 传到详情页并定位。
- [ ] 手动核对设计文档验收标准：姓名出会话、号码出会话、关键词出短信、姓名不刷全量历史短信、筛选仍可用、无权限时有提示、结果展示显示名。
- [ ] 确认联系人 picker、chip 关闭、未读 / SIM / 彩信筛选没有回归。
