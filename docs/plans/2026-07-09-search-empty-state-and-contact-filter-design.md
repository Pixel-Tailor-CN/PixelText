# 搜索空状态与号码筛选设计

## 方案演进

本需求最初采用搜索框右侧联系人按钮、`contactAddress`／`contactDisplayName` 和仓库末端内存过滤。该方案已被[搜索筛选重设计](2026-09-06-search-filters-redesign-design.md)替代；以下记录当前保留的空状态和号码筛选行为。

## 当前交互

无搜索词且无有效筛选时，`SearchUiState.Idle` 通过 `SearchResultList` 显示 `illustration_search_idle` 和“搜索短信和彩信”。空结果使用独立的 `illustration_search_empty`。布局由共享 `AppEmptyState` 控制，不再要求本页面固定向上偏移 48–64dp。

搜索框下方为横向筛选栏，包含发件人号码、已读状态、SIM、消息类型和日期。“发件人号码”进入 `SearchPhoneScreen`，可手动输入或从系统联系人选择一个号码；保存后替换当前号码条件，清除操作也在号码页提供。不是搜索框右侧按钮加独立可关闭联系人 chip 的旧布局。

没有关键词但有有效号码条件时仍执行搜索；号码与其他筛选共同作用于 SMS/MMS 镜像。姓名只作为显示信息，不作为号码查询条件。

## 模型与查询

当前模型位于 `domain/model/search/MessageSearchFilter.kt`，字段为 `phoneNumber`、`phoneDisplayName`、`unreadOnly`、`simSubIds`、`transports` 和 `date`。类型条件只有单选 SMS 或 MMS 时生效；全部／未选等价于不限。

`SearchViewModel` 构建 `MessageSearchRequest`，通过 `MessageRepository.searchMessages(request)` 查询本地镜像。`MirrorSearchQuery`／`MirrorSearchDao` 负责号码归一化及参数化 SQL 条件，不能恢复为查询所有消息后在内存匹配联系人。号码规则以 `SearchPhoneNumbers` 为准，不再直接使用 `ContactDataSource.matchesAddress()` 作为搜索过滤器。

系统联系人选择由 `PickedPhoneSource` 读取返回 URI；选择失败时提示手动输入，不要求先申请整个通讯录权限或默认短信角色来选择号码。其他短信访问的权限与角色约束不因此改变。

## 文件与资源

代码路径相对 `app/src/main/java/vip/mystery0/pixel/text/`：

- `ui/message/search/SearchScreen.kt`、`SearchFilterBar.kt`：搜索与筛选入口。
- `ui/message/search/SearchPhoneScreen.kt`、`SearchViewModel.kt`：号码编辑、选择和查询状态。
- `ui/message/search/SearchResultList.kt`、`ui/component/AppEmptyState.kt`：空状态。
- `data/source/PickedPhoneSource.kt`、`domain/model/search/SearchPhoneNumbers.kt`：所选号码读取与规范化。
- `data/db/mirror/MirrorSearchQuery.kt`、`MirrorSearchDao.kt`：本地 SQL 搜索。
- 插图：`app/src/main/res/drawable/illustration_search_idle.xml`。早期计划要求的原始 SVG 未保留在当前仓库，不能把 `docs/assets/search_idle_illustration.svg` 当作可用源文件。

## 验证

早期计划没有单独记录本需求的运行验收结果；后续已验与待验范围见[搜索实施计划](2026-09-06-search-filters-redesign.md)。本次只核对源码，不把后续部分验收扩展为全部场景通过。

维护时按改动编译，并检查无输入空状态、键盘与大字体布局、手动输入／联系人选择／清除、仅号码搜索、与其他筛选叠加及关键词高亮。不新增或运行单元测试。
