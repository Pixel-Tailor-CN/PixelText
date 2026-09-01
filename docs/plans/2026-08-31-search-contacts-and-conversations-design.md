# 搜索支持联系人与会话命中设计

## 背景

用户反馈：搜索框无法按联系人找到会话，只能匹配短信正文。存量到万级时，列表不可扫，搜索是唯一找回路径。有用户因此误以为是未读未扫全，甚至删掉历史短信后才确认是产品缺口。

当前搜索页已经具备：

- 关键词搜索短信 / 彩信正文和彩信主题
- 未读、SIM、彩信筛选
- 通过系统联系人选择器设置单个联系人筛选 chip

但这些能力对不上用户心智。用户在搜索框输入「张三」时期望先看到张三的会话，而不是先打开通讯录再过滤。

现状与预期的差距：

- `TelephonyDataSource.searchSmsMessages()` 只查询 `BODY LIKE`，不查 `ADDRESS`，也不查联系人显示名。
- `SearchViewModel` 只调用 `searchMessages()`。仓库里已有的 `searchConversations()` / `searchConversationThreadIds()` 未被搜索页使用。
- 联系人姓名存在 `ContactDataSource` 内存缓存中，不在 Telephony 表里，所以输入人名必然空结果。
- 搜索结果标题使用 `message.sender` 原始号码，不显示联系人姓名。
- 占位文案是「搜索短信」，强化了「只搜正文」的错误预期。
- 右侧选人按钮是「先选人再过滤」，不是「在输入框搜人」。

2026-07-09 的联系人筛选设计明确不改造底层查询语义。本次要改的是搜索语义本身。

## 目标

- 搜索框输入联系人姓名、号码片段或服务号资料名时，优先返回匹配会话。
- 搜索框输入正文关键词时，继续返回匹配短信，并保留关键词高亮。
- 姓名与正文同时命中时，结果分区展示：会话在上，短信在下。
- 搜索结果展示联系人 / 服务号显示名，号码作为次要信息。
- 占位与空状态文案改为同时覆盖联系人和短信。
- 保留现有联系人 picker 筛选，以及未读 / SIM / 彩信筛选。
- 无 `READ_CONTACTS` 时仍能按号码搜索会话；按姓名搜不到时给出明确提示，而不是只有空白。

## 非目标

- 不在本次实现收藏 / 加星。
- 不引入拼音、模糊音、多音字或在线搜索。
- 不支持一次选择多个联系人。
- 不新增设置项。
- 不把命中会话里的全部历史短信平铺到短信结果中。
- 不改造会话列表页为即时过滤；全局搜索仍走独立搜索页。
- 不新增单元测试；按项目约束使用编译和手动检查验证。

## 用户故事与验收

### 用户故事

作为短信很多、很少翻列表的用户，我希望在搜索框输入联系人姓名、备注名或号码时，优先看到对应会话；输入关键词时再看到命中的短信。这样我不需要先打开通讯录，也不需要靠记忆正文片段。

### 验收标准

- 输入通讯录显示名或其连续子串，例如「张三」「张」，顶部出现匹配会话，点击进入该会话。
- 输入号码片段，例如 `138`、`10086`、`+86`，同样命中对应会话。
- 输入服务号资料名（`SenderProfile.displayName`）也能命中会话。
- 输入正文关键词后，短信结果仍按原逻辑高亮关键词。
- 姓名只命中会话、正文未命中时，短信分区不出现该会话的全部历史短信。
- 姓名和正文同时命中时，会话分区和短信分区都展示。
- 现有联系人 chip、未读、SIM、彩信筛选仍然可用，并作用于对应结果。
- 无联系人权限时，号码搜索仍可用；姓名搜不到时，空状态提示可申请联系人权限，而不是只显示「没有找到匹配的短信」。
- 搜索框占位改为「搜索联系人或短信」。
- Idle 辅助文案改为同时说明可以输入姓名、号码或关键词。
- 结果标题优先显示联系人姓名或服务号名称，号码作为次要信息。
- 会话结果点击进入该会话；短信和彩信结果点击进入该会话并尽量定位到该条消息。

## 搜索语义

一次查询产出两类互相独立的结果。

### 会话命中

query 非空时，会话命中任一即可入选：

- 联系人显示名包含 query，忽略大小写。
- 会话 `address` 的归一化 key 集合中，任一 key 包含 query 的数字/号码形态。
- 服务号资料名包含 query，忽略大小写。

匹配使用简单 `contains`，不做分词，不做拼音。

### 短信命中

query 非空时，短信命中任一即可入选：

- 短信 `BODY` 包含 query
- 彩信纯文本 part 包含 query
- 彩信主题包含 query

短信结果**不**因为发件人姓名或号码命中而自动入选。避免输入「张三」后把该会话全部历史短信刷屏。

号码搜索的会话入口只放在会话分区。若用户同时希望看该号码作为正文出现的短信，正文命中自然会进入短信分区。

### query 为空

与现有行为一致：

- 无任何筛选：`Idle`，展示空状态插图
- 仅有筛选（包括联系人 chip）：只查短信，不查会话分区

联系人 picker 仍然表示「只看这个人的短信」，不是「搜索这个人」。输入框搜人与 chip 滤人可以并存：chip 收窄会话分区和短信分区的号码范围。

### 筛选叠加

| 筛选 | 会话分区 | 短信分区 |
| --- | --- | --- |
| 联系人 chip | 只保留号码匹配该联系人的会话 | 保持现有号码归一化过滤 |
| 未读 | 只保留 `unreadCount > 0` 的会话 | 保持现有 `READ = 0` |
| SIM | 会话模型没有卡槽字段，不在会话分区过滤 | 保持现有 `SUBSCRIPTION_ID` |
| 彩信 | 只保留 `hasMms == true` 的会话 | 保持现有仅查彩信 |

### 可见性

- 会话分区排除已归档会话，与会话列表一致。
- 开启骚扰隔离时，会话分区排除骚扰会话，与会话列表一致。
- 短信分区保持现有 Telephony 全量检索，不额外按归档或骚扰隔离裁剪，避免改变旧的正文搜索召回。

### 权限

- 无 `READ_CONTACTS`：不根据联系人姓名命中会话；号码和服务号资料名仍可命中。
- 若 query 含非数字字符、会话与短信都为空、且没有联系人权限，空状态增加一句「授予联系人权限后可按姓名搜索」。
- 不在每次输入时自动弹权限框。沿用现有选人按钮的权限申请流程，用户需要时再申请。

### 排序与数量

- 会话按 `timestamp` 倒序，最多 20 条。
- 短信按 `timestamp` 倒序，最多 100 条。这 100 是合并短信和彩信、再完成联系人 / 未读 / SIM / 彩信筛选之后的总上限，不是 SMS 查询 100 加 MMS 查询 100。
- 两类结果各自独立排序，不为「同会话去重」打乱时间序。

## 结果模型

新增搜索聚合结果，不再让 `SearchUiState.Success` 只持有 `List<MessageModel>`。

```kotlin
data class SearchResults(
    val conversations: List<ConversationModel> = emptyList(),
    val messages: List<MessageModel> = emptyList(),
) {
    val isEmpty: Boolean
        get() = conversations.isEmpty() && messages.isEmpty()
}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val results: SearchResults) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
```

点击事件拆成两个回调：

- `onConversationClick(conversation: ConversationModel)`
- `onMessageClick(message: MessageModel)`

导航：

- 会话点击：`conversationDetailRoute(threadId, address)`
- 消息点击：`conversationDetailRoute(threadId, sender, messageId = message.id)`

`MessageModel` 里彩信 ID 是负数（`-mmsId`）。现有 `conversationDetailRoute()` 和路由解析只保留 `messageId > 0`，并把 `-1` 当作缺省 sentinel。直接把 `message.id` 传进去会让彩信搜索结果无法定位。

实现时必须：

- 路由只把 `-1` 视为「未指定消息」，接受其他任何非 `-1` 的 ID，包括负数彩信 ID
- 详情页用 `message.id` 相等比较定位，不要再用 `id > 0` 把彩信过滤掉

## UI

### 顶部栏

结构不变：返回、输入框、清空、联系人按钮。

文案：

- 输入框占位：「搜索联系人或短信」
- Idle：「输入姓名、号码或关键词搜索」
- 全空结果：「没有找到匹配的联系人或短信」
- 无联系人权限且 query 像姓名：「授予联系人权限后可按姓名搜索」

### 结果列表

`LazyColumn` 分区：

1. 若有会话结果，先 sticky / 普通 section header「会话」，再渲染会话行。
2. 若有短信结果，再 section header「短信」，再渲染现有 `SearchResultItem`。

会话行不新造复杂组件，对齐会话列表信息密度：

- 左侧头像：优先服务号头像，否则沿用现有颜色 + 联系人图标
- 主标题：`displayName ?: address`
- 副标题：最近一条 `snippet`，单行省略
- 右侧时间：复用 `formatTimeShort`

短信行改造：

- 主标题改为显示名，不再只显示原始号码
- 显示名来源与会话列表相同：联系人缓存，其次服务号资料，最后号码
- 副标题保持正文 snippet 和高亮

没有会话结果时不显示「会话」标题；没有短信结果时不显示「短信」标题。

## 数据流

```text
SearchViewModel
  query.debounce(300) + filter
        |
        v
MessageRepository.search(query, filter)
        |
        +--> 会话：会话缓存
        |      + ContactDataSource 姓名 / 号码匹配
        |      + SenderProfile 显示名匹配
        |      + 归档 / 骚扰隔离可见性
        |
        +--> 短信：Telephony BODY / 主题检索
               + 联系人 chip 号码归一化过滤
        |
        v
SearchResults(conversations, messages)
```

推荐把现有 `searchMessages()` 留作内部实现，对外增加：

```kotlin
fun search(
    query: String,
    filter: MessageSearchFilter = MessageSearchFilter(),
): Flow<SearchResults>
```

`SearchViewModel` 改调 `search()`。旧的 `searchMessages()` 和 `searchConversations()` 可以保留，避免无关注册点大面积改动，但搜索页不再直接依赖它们。

### 会话检索实现

不要用 `searchConversationThreadIds()` 作为主路径。它只查 `ADDRESS LIKE` 和 `BODY LIKE`，仍然搜不到联系人姓名，而且会把正文命中的 thread 混进会话结果。

主路径：

1. 读取会话缓存 `ConversationCacheRepository.observeAllConversations()` / 现有 `getAllConversations()` 同源数据。
2. 用与会话列表相同的方式补 `displayName`、服务号头像和名称。
3. 去掉已归档 thread。
4. 若开启骚扰隔离，去掉骚扰会话。
5. 在内存中按姓名、号码、服务号名过滤。
6. 再叠加联系人 chip / 未读 / 彩信筛选。
7. 按时间倒序截断到 20 条。

`ContactDataSource` 需要补反向查找，而不是只支持 `address -> name`：

```kotlin
fun findAddressesByDisplayName(query: String): Set<String>
fun matchesQuery(address: String, displayName: String?, query: String): Boolean
```

号码匹配继续复用现有 `contactLookupKeys()`：

- 原始号码
- `PhoneNumberUtils.normalizeNumber`
- 纯数字
- 去掉 `86` / `0086` 后的号码

query 本身也生成一份 lookup keys；两边有包含关系即命中。纯汉字 query 不会进入号码匹配。

### 短信检索实现

保留 `searchSmsMessages()` / `searchMmsMessages()` 的正文和主题查询。

本次允许的小改动：

- 不把 `ADDRESS LIKE` 并入短信查询。号码找人只走会话分区。
- 查询层可以加安全上限，避免万级 `BODY LIKE` 无上限扫表；这个上限只是防卡死，不是产品上的 100 条。
- 产品上限 100 必须在仓库层一次性落在：合并 SMS + MMS → 联系人 / 未读 / SIM / 彩信筛选 → 按时间倒序截断到 100。
- 有联系人 chip 时，不能先对全量结果 `LIMIT` 再过滤号码。否则更新的其他发件人正文命中会把该联系人较旧的命中挤掉。优先把号码约束推进查询；若 Telephony 无法复用归一化匹配，就先过量取回再在仓库层按 `matchesAddress()` 过滤，最后才截 100。

## 代码结构

### 需要新增

- `app/src/main/java/vip/mystery0/pixel/text/domain/model/SearchResults.kt`
  - 或放在 `MessageRepository.kt` 旁的搜索模型文件中
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchConversationResultItem.kt`
  - 会话结果行

### 需要修改

- `app/src/main/java/vip/mystery0/pixel/text/data/source/ContactDataSource.kt`
  - 增加按显示名反查号码、统一 query 匹配方法
- `app/src/main/java/vip/mystery0/pixel/text/domain/repository/MessageRepository.kt`
  - 增加 `search()` 与 `SearchResults`
- `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`
  - 实现会话 + 短信聚合搜索
- `app/src/main/java/vip/mystery0/pixel/text/data/source/TelephonyDataSource.kt`
  - 短信 / 彩信正文搜索可加安全上限，但不要把产品 100 落在单侧查询上
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchViewModel.kt`
  - 改用聚合搜索结果
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchScreen.kt`
  - 占位文案、双回调、权限空态文案
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultList.kt`
  - 分区列表
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultItem.kt`
  - 展示显示名
- `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
  - 会话点击；消息点击传 `messageId`，且路由接受负数彩信 ID
- 会话详情页相关定位逻辑
  - 确认 `targetMessageId` 能定位负数 ID

### 不需要改

- 联系人 picker、权限对话框、联系人 chip 交互可以保持现有实现。
- 会话列表、归档、骚扰列表、验证码页不在本次范围。
- 不新增网络接口、设置项或数据库表。联系人姓名继续用现有内存缓存。

## 风险与取舍

- 会话缓存未 ready 时，搜索会话可能暂时为空。实现上应等待或触发与列表相同的缓存同步，避免「列表里有人、搜索里没有」。
- 联系人缓存目前是一次性加载。联系人变更后姓名可能滞后，可接受；不要为这次搜索做联系人 ContentObserver。
- 服务号资料名依赖本地 / Hub 资源，未下载资料时只能靠号码命中。
- 短信结果上限 100 条会改变「全文扫完」的旧行为，但这比万级无 LIMIT 卡死更符合搜索页。
- 中文姓氏单字会召回较多会话，靠 20 条上限和时间倒序控制。不做额外相关度算法。

## 验证方案

按项目约束，不新增单元测试，采用以下方式验证：

- 运行 `./gradlew :app:compileDebugKotlin`
- 手动检查搜索页：
  - Idle 文案为「输入姓名、号码或关键词搜索」
  - 输入联系人姓名出现会话分区
  - 输入号码片段出现会话分区
  - 输入正文关键词出现短信分区和高亮
  - 姓名命中但正文未命中时，不刷出该会话全部短信
  - 联系人 chip、未读、SIM、彩信筛选仍可用
  - 联系人 chip + 关键词时，结果仍是该联系人的命中，不被更新的其他发件人挤掉
  - 关闭联系人权限后，号码仍能搜到会话，姓名空结果有权限提示
  - 点击会话进入对应详情
  - 点击短信进入对应详情
  - 点击彩信搜索结果能定位到该条彩信，而不是只打开会话顶部
  - 结果标题显示联系人姓名而不是纯号码
