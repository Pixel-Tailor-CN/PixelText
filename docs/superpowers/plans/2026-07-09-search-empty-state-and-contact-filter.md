# 搜索页空状态插图与联系人筛选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为搜索页新增空状态插图与单选联系人筛选，让用户可以直接按联系人范围搜索短信。

**Architecture:** 保持现有搜索入口、搜索状态和搜索结果列表结构不变，在 `MessageSearchFilter` 中扩展联系人筛选字段，并在 `MessageRepositoryImpl` 中复用联系人号码归一化逻辑执行最终过滤。UI 层负责联系人选择、chip 展示和空状态插图渲染，不承载实际过滤逻辑。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Activity Result API、Android VectorDrawable、Koin

## Global Constraints

- 使用中文回复，生成的代码注释和文档使用中文，日志打印使用英文。
- UI 必须使用 Jetpack Compose，不新增 XML layout 文件。
- 优先沿用现有架构、命名和包边界。
- 不引入第三方搜索或联系人选择依赖。
- 本项目不做单元测试；除非用户明确要求，否则不要新增 `app/src/test/`、`app/src/androidTest/` 测试代码，不要新增测试依赖，也不要运行 `test` 相关任务。
- 验证优先使用编译、Lint、Mock 界面和手动检查。

---

### Task 1: 扩展搜索过滤模型与仓库逻辑

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/repository/MessageRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/source/ContactDataSource.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`

**Interfaces:**
- Consumes:
  - `MessageSearchFilter`
  - `ContactDataSource.getDisplayName(address: String): String?`
- Produces:
  - `MessageSearchFilter(contactAddress: String?, contactDisplayName: String?)`
  - `ContactDataSource.matchesAddress(selectedAddress: String, candidateAddress: String): Boolean`
  - 搜索结果上的联系人过滤逻辑

- [ ] 在 `MessageSearchFilter` 中新增联系人号码和联系人显示名字段。
- [ ] 更新搜索过滤激活判断，把联系人筛选纳入活跃条件。
- [ ] 在 `ContactDataSource` 中增加公开的号码匹配方法，复用现有归一化 key 逻辑。
- [ ] 在 `MessageRepositoryImpl.searchMessages()` 中对已合并的短信 / 彩信结果增加联系人过滤。
- [ ] 保持 Telephony 搜索接口不变，只在仓库层做最终联系人筛选。

### Task 2: 扩展搜索 ViewModel 状态与操作

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchViewModel.kt`

**Interfaces:**
- Consumes:
  - `MessageSearchFilter`
  - `MessageRepository.searchMessages(query: String, filter: MessageSearchFilter)`
- Produces:
  - `fun setContactFilter(address: String, displayName: String?)`
  - `fun clearContactFilter()`

- [ ] 为 `SearchViewModel` 增加设置联系人筛选和清除联系人筛选的方法。
- [ ] 保持现有 `combine(query, filter)` 搜索触发模型不变。
- [ ] 确保“仅联系人筛选、无关键词”时也会进入搜索而不是回到 `Idle`。

### Task 3: 新增插图资源并替换空状态

**Files:**
- Create: `docs/assets/search_idle_illustration.svg`
- Create: `app/src/main/res/drawable/illustration_search_idle.xml`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultList.kt`

**Interfaces:**
- Consumes:
  - `SearchUiState.Idle`
  - `painterResource(R.drawable.illustration_search_idle)`
- Produces:
  - 搜索页空状态插图显示

- [ ] 生成原始 SVG 源文件，保存到 `docs/assets/search_idle_illustration.svg`。
- [ ] 生成可在 Android 中使用的导入后 `VectorDrawable` 资源。
- [ ] 在 `SearchResultList` 中将 `Idle` 空状态从文字替换为插图 + 辅助文案。
- [ ] 让插图在结果区内整体上移，避免键盘遮挡。

### Task 4: 实现搜索页联系人选择与 chip 交互

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchScreen.kt`

**Interfaces:**
- Consumes:
  - `SearchViewModel.setContactFilter(address: String, displayName: String?)`
  - `SearchViewModel.clearContactFilter()`
  - `MessageSearchFilter.contactAddress`
  - `MessageSearchFilter.contactDisplayName`
  - `createDefaultSmsAppRequestIntent()`
  - `isDefaultSmsApp()`
- Produces:
  - 搜索框右侧联系人按钮
  - 联系人选择器流程
  - 单个可关闭联系人 chip

- [ ] 把顶部搜索输入区域改成 `BasicTextField + 联系人按钮 + 清空按钮` 的同一行结构。
- [ ] 复用联系人选择流程：默认短信应用检查、权限申请、系统联系人选择器。
- [ ] 选择联系人后读取号码和显示名，写入 ViewModel 筛选状态。
- [ ] 在 chip 行中新增单个联系人 chip，展示联系人名或号码。
- [ ] 关闭联系人 chip 时清除联系人筛选。

### Task 5: 编译验证与静态回归

**Files:**
- No code changes required unless verification reveals issues.

**Interfaces:**
- Consumes: 上述全部实现
- Produces: 可交付的验证结果与必要修正

- [ ] 运行 `./gradlew :app:compileDebugKotlin`。
- [ ] 检查搜索页的无输入空状态、联系人筛选、chip 清除、仅联系人搜索和关键词叠加搜索逻辑。
- [ ] 确认没有影响现有未读 / SIM / 彩信筛选。
