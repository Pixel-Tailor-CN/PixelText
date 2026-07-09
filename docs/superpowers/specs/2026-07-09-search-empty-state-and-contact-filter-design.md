# 搜索页空状态插图与联系人筛选设计

## 背景

当前搜索页存在两个体验问题：

- 没有输入内容时，页面中部仅显示一行提示文字，信息密度低，视觉层次弱。
- 搜索只能依赖关键词、未读、SIM 和彩信筛选，无法直接限定某个联系人。

本次设计目标是在不改变搜索页整体导航结构的前提下，增强空状态展示，并加入单选联系人筛选能力。

## 目标

- 将搜索页 `Idle` 空状态从文字替换为插图。
- 插图在视觉上位于内容区域中心偏上的位置，避免软键盘遮挡。
- 在搜索输入框右侧增加联系人入口。
- 允许用户从系统联系人中选择 1 个联系人作为独立筛选条件。
- 已选联系人以单个 chip 展示，可点击关闭取消筛选。
- 当仅有联系人筛选且搜索框为空时，直接展示该联系人的全部短信结果。

## 非目标

- 不支持同时选择多个联系人。
- 不引入新的设置项。
- 不改造消息搜索的底层 Telephony 查询接口签名。
- 不新增单元测试；按项目约束使用编译和手动检查验证。

## 数据模型

扩展 `MessageSearchFilter`，新增联系人筛选字段：

- `contactAddress: String?`
- `contactDisplayName: String?`

其中：

- `contactAddress` 是实际搜索过滤条件
- `contactDisplayName` 只用于 UI 上展示 chip 文案

筛选激活条件更新为：

- `unreadOnly == true`
- 或 `simSubId != null`
- 或 `mmsOnly == true`
- 或 `contactAddress != null`

## 搜索行为

### 无关键词、无筛选

- `SearchUiState` 为 `Idle`
- 结果区显示空状态插图

### 有联系人筛选、无关键词

- 直接执行搜索
- 展示该联系人的全部短信 / 彩信结果

### 有联系人筛选、有关键词

- 先按现有关键词逻辑搜索短信内容
- 再按联系人号码过滤结果

### 联系人匹配规则

联系人筛选不做纯字符串完全相等比较，而是复用现有联系人号码归一化逻辑：

- 保留原始号码
- 归一化号码
- 纯数字号码
- 去掉 `86` / `0086` 国家码后的号码

只要消息发送方号码和所选联系人号码在归一化 key 集合上有交集，即视为匹配。

## UI 结构

### 顶部栏

顶部栏仍然是返回按钮 + 搜索输入区域，但输入区域右侧新增联系人按钮：

- 联系人按钮始终显示
- 清空按钮仅在搜索词非空时显示

实现上继续使用现有 `TopAppBar`，输入框和联系人/清空按钮放在同一个标题行中，保证视觉上确实位于输入框右侧。

### 筛选 chip 行

联系人筛选加入现有 chip 行：

- 如果未选联系人，则不显示联系人 chip
- 如果已选联系人，则显示 1 个联系人 chip
- chip 文案优先显示联系人名，兜底显示号码
- chip 带 `x` 图标，点击后清除联系人筛选

其余筛选项保留：

- 未读
- SIM 1
- SIM 2
- 彩信

## 联系人选择交互

复用会话列表页现有联系人选择流程：

- 已有联系人权限时，直接打开系统联系人选择器
- 没有联系人权限时，先走默认短信应用检查，再申请 `READ_CONTACTS`
- 选中联系人后，读取：
  - 电话号码
  - 联系人显示名
- 用新联系人覆盖旧联系人筛选

同一时间只允许保留 1 个联系人筛选，不做多选或堆叠。

## 空状态插图

### 资源形式

为了满足“先生成 SVG，再导入 Android”：

- 保存一份原始 SVG 源文件到文档目录
- 在 `res/drawable/` 中新增导入后的 Android `VectorDrawable`

### 视觉方向

插图采用轻量的 Material 风格：

- 中央是一张短信卡片
- 上方叠加一个放大镜
- 周围点缀联系人节点和搜索轨迹元素
- 颜色采用偏蓝绿色和中性色，适配浅色 / 深色背景

### 布局方式

`Idle` 状态使用单独 composable：

- 内容区域仍然整体 `Box` 居中
- 插图容器在视觉中心基础上向上偏移约 `48dp ~ 64dp`
- 插图下方保留一行简短辅助文案

这样在搜索页自动聚焦并弹出软键盘后，插图和提示文字仍能留在用户可见区域。

## 代码结构

### 需要新增

- `app/src/main/res/drawable/illustration_search_idle.xml`
  - Android 可用的导入后插图资源
- `docs/assets/search_idle_illustration.svg`
  - 原始 SVG 源文件

### 需要修改

- `app/src/main/java/vip/mystery0/pixel/text/domain/repository/MessageRepository.kt`
  - 扩展 `MessageSearchFilter`
- `app/src/main/java/vip/mystery0/pixel/text/data/source/ContactDataSource.kt`
  - 暴露号码匹配辅助方法
- `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`
  - 在搜索结果上增加联系人过滤
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchViewModel.kt`
  - 增加设置 / 清除联系人筛选能力
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchScreen.kt`
  - 顶部联系人入口、联系人 chip、联系人选择流程
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/search/SearchResultList.kt`
  - 空状态改为插图

## 验证方案

按项目约束，不新增单元测试，采用以下方式验证：

- 运行 `./gradlew :app:compileDebugKotlin`
- 手动检查搜索页：
  - 无输入无筛选时显示插图
  - 插图位置在中部偏上
  - 联系人按钮可打开联系人选择器
  - 选中联系人后出现单个 chip
  - 关闭 chip 后联系人筛选被清除
  - 仅联系人筛选时能直接显示该联系人的所有消息
  - 联系人筛选与未读 / SIM / 彩信筛选可叠加
  - 有关键词时结果仍按原逻辑高亮关键词
