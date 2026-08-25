# 会话详情主题自定义设计

## 背景

Issue #9 要求改善会话详情页短信原文的可读性，并提供会话详情显示自定义能力。本期一次性实现 Issue 中 Phase 1 和 Phase 2 的内容，但不修改智能卡片配色。

该能力后续可能扩展为覆盖所有页面的“主题管理”，并支持主题导入、导出和用户分发。因此，本期实现应建立可版本化、可按页面扩展的主题配置基础设施，但只保存一个当前主题，不提前实现主题库、导入导出或主题切换。

## 范围

本期支持：

- 日间和暗黑两套独立配置。
- 接收、发送原文气泡的文字颜色分别配置。
- 接收、发送原文气泡的背景颜色分别配置。
- 输入区域背景颜色。
- 输入框提示文字。
- 是否显示 SIM 信息。
- 会话详情文字缩放。
- 日间、暗黑分别设置背景图片。
- Material 3 颜色角色、HSV 颜色选择器和 HEX 输入。
- 实时预览、显式保存、未保存退出提醒和恢复默认。
- 系统高对比度文字模式优先。

本期不支持：

- 智能卡片颜色配置。验证码、银行、铁路、快递等智能卡片保持现有配色；其主容器使用主题强调色与 Material 3 表面色合成的最终不透明颜色，避免背景图片透出后降低可读性。
- 背景图片统一透明度。后续按上层组件分别增加透明度配置。
- 会话详情独立时间格式。预览和真实页面始终读取现有全局 `messageTimeDisplayFormat`。
- 多个命名主题、主题导入导出及主题文件分发。
- 气泡圆角、间距等扩展项。

背景图片只覆盖消息列表区域，不覆盖 TopAppBar 和底部输入区域。

## 总体架构

采用集中式“配置 + 有效样式解析”方案：

1. `ThemeConfigurationRepository` 保存可版本化的原始主题配置。
2. 设置页面维护独立草稿，预览只消费草稿。
3. 用户点击保存后，草稿才写入 Repository 并影响真实会话。
4. `ConversationDetailStyleResolver` 根据主题配置、当前深浅模式、Material 3 `ColorScheme` 和系统高对比度状态生成有效样式。
5. 真实会话和 Mock 预览共用同一个 resolver 和核心渲染组件。

```text
ThemeConfigurationRepository
        │ persisted StateFlow
        ▼
CustomizationViewModel ──复制──► Draft
        │                           │
        │                    设置页实时预览
        │                           │
        └────点击保存◄──────────────┘
                    │
          校验配置、提交图片
                    │
          持久化主题 JSON
                    │
             更新 StateFlow
                    ▼
       ConversationDetailScreen
                    │
     resolveConversationDetailStyle
                    │
         真实会话立即重新渲染
```

## 主题数据模型

顶层模型从一开始按页面模块组织：

```kotlin
data class ThemeConfiguration(
    val schemaVersion: Int = 1,
    val conversationDetail: ConversationDetailThemeModule =
        ConversationDetailThemeModule(),
)
```

未来可在顶层增加 `conversationList`、`settings` 等模块，不需要破坏会话详情配置。

```kotlin
data class ConversationDetailThemeModule(
    val light: ConversationDetailAppearance = ConversationDetailAppearance(),
    val dark: ConversationDetailAppearance = ConversationDetailAppearance(),
    val showSimInfo: Boolean = true,
    val inputPlaceholder: String = "请输入",
    val textScale: Float = 1f,
)

data class ConversationDetailAppearance(
    val receivedTextColor: ThemeColorReference? = null,
    val sentTextColor: ThemeColorReference? = null,
    val receivedBubbleColor: ThemeColorReference? = null,
    val sentBubbleColor: ThemeColorReference? = null,
    val inputBackgroundColor: ThemeColorReference? = null,
    val backgroundImage: ThemeImageReference? = null,
)
```

`null` 表示使用官方默认值。

颜色保存语义引用，不保存当前设备解析后的最终颜色：

```kotlin
data class ThemeColorReference(
    val type: ThemeColorType,
    val value: String,
)

enum class ThemeColorType {
    MATERIAL_ROLE,
    CUSTOM_ARGB,
}
```

- `MATERIAL_ROLE` 的值是稳定的角色存储名。
- `CUSTOM_ARGB` 的值是标准化后的 `#AARRGGBB`。
- 运行时只接受明确的 Material 3 角色白名单。
- 未知角色或非法颜色只回退对应字段，不使整个主题失效。

背景图片保存逻辑资源 ID，而不是绝对路径：

```kotlin
data class ThemeImageReference(
    val assetId: String,
)
```

这为未来将 `theme.json` 与 `assets/` 打包为独立主题文件保留稳定边界。

## 持久化

主题使用独立 SharedPreferences，不与业务设置混合：

```kotlin
object ThemeConfigurationKeys {
    const val PREFS_NAME = "theme_configuration"
    const val KEY_CURRENT_THEME = "current_theme"
}
```

`KEY_CURRENT_THEME` 使用现有 Moshi 依赖保存完整 JSON。`schemaVersion` 同时包含在 JSON 内，作为主题内容版本依据。

职责划分：

- `app_settings`：短信行为、通知、垃圾识别、全局时间格式等功能偏好。
- `theme_configuration`：可随未来主题文件导出和分发的视觉配置。
- `filesDir/theme_assets/`：当前主题引用的正式图片资源。
- `cacheDir/theme_drafts/`：尚未保存的页面草稿图片。

接口边界：

```kotlin
interface ThemeConfigurationRepository {
    val configuration: StateFlow<ThemeConfiguration>

    suspend fun save(configuration: ThemeConfiguration): Result<Unit>
    suspend fun resetAll(): Result<Unit>
}

interface ThemeAssetRepository {
    suspend fun createDraftBackground(
        mode: ThemeMode,
        source: Uri,
    ): ThemeImageDraft

    suspend fun commitDraft(draft: ThemeImageDraft): ThemeImageReference
    suspend fun discardDraft(draft: ThemeImageDraft)
    suspend fun deleteAsset(reference: ThemeImageReference)
    fun resolve(reference: ThemeImageReference): File?
}
```

Repository 的具体方法可按实现需要收敛，但必须保持主题 JSON 与图片生命周期分离。

## 旧设置迁移

现有 `conversationDetailTextScale` 从 `app_settings` 迁移到主题配置：

1. 检查独立主题 Preferences 是否已经存在主题 JSON。
2. 若不存在，读取旧 `KEY_CONVERSATION_DETAIL_TEXT_SCALE`。
3. 使用旧缩放值生成 `schemaVersion = 1` 的默认主题。
4. 通过可检查结果的 `SharedPreferences.commit()` 写入主题 JSON。
5. 写入成功后删除旧键。
6. 写入失败则保留旧键，下次启动重试。

迁移完成后，`ConversationDetailScreen` 只从主题 Repository 获取文字缩放，避免双数据源。

全局 `messageTimeDisplayFormat` 保持在 `AppSettingsRepository`，不会进入主题配置、草稿或保存事务。

## 有效样式解析

UI 层定义页面级有效样式，并按组件继续分组，避免未来扩展成巨型扁平模型：

```kotlin
@Immutable
data class ResolvedConversationDetailStyle(
    val originalMessage: ResolvedOriginalMessageStyle,
    val inputArea: ResolvedInputAreaStyle,
    val background: ResolvedConversationBackgroundStyle,
    val showSimInfo: Boolean,
    val inputPlaceholder: String,
    val textScale: Float,
    val customizationSuppressed: Boolean,
)
```

智能卡片本期不加入有效样式模型。卡片继续使用自身的强调色，但主容器必须将低透明度强调色预先合成到 Material 3 `surfaceContainer` 上，得到最终不透明颜色；不得直接把透明容器叠在会话背景图片上。未来可独立增加 `ResolvedSmartCardStyle`，再由 `MessageCardFactory` 消费。

解析优先级固定为：

1. 系统高对比度方案。
2. 当前日间或暗黑模式的用户配置。
3. 官方默认样式。

官方默认样式：

- 接收、发送正文：`onSurface`，替代当前对比度偏低的 `onSurfaceVariant`。
- 接收、发送气泡：`surfaceVariant`，保持现有整体视觉。
- 输入区域：保持现有 `surfaceVariant` 风格。
- 无背景图片。
- 显示 SIM 信息。
- placeholder 为“请输入”。
- 文字缩放为 `1f`。

MD3 角色在渲染时从当前 `MaterialTheme.colorScheme` 解析，使动态取色在壁纸变化后继续生效。

## 高对比度模式

通过 `AccessibilityManager.isHighTextContrastEnabled` 和状态变化监听暴露 `StateFlow<Boolean>`。

开启时：

- 忽略用户自定义文字颜色和气泡颜色。
- 忽略输入区域自定义颜色。
- 忽略背景图片。
- 强制使用系统高对比度正文与表面方案。
- SIM 显示、placeholder 和文字缩放继续有效。
- 设置页面相关颜色和背景控件置灰，并说明自定义暂时失效。
- 预览区同步展示真实的高对比度效果。
- 不删除或覆盖用户配置，关闭高对比度后自动恢复。

本期不重构智能卡片主题；智能卡片继续使用其现有 Material 3 配色。

## UI 组件边界

样式在页面入口解析一次并显式向下传递：

```text
ConversationDetailScreen
 ├─ ConversationBackground
 ├─ MessageItem
 │   ├─ OriginalTextCard
 │   └─ MessageCardFactory（本期不接收主题配置）
 └─ ConversationInputBar
```

调整原则：

- `MessageItem` 根据 `message.isReceived` 选择接收或发送原文样式。
- `OriginalTextCard` 不再自行决定普通状态下的文字和气泡颜色。
- 多选状态仍强制使用 `inverseSurface` 和 `inverseOnSurface`，保证交互反馈稳定。
- 链接优先使用 `primary`；如果与自定义气泡对比度不足，则使用正文色并保留下划线。
- SIM 标签由 `showSimInfo` 控制。
- 智能卡片保持自身现有强调色，并统一使用不透明的合成表面色作为主容器背景。
- 背景图片只绘制在消息列表底层。

## 配置页面

新增 `ConversationDetailCustomizationScreen`，结构为：

```text
TopAppBar + 全局保存按钮
高对比度提示
固定高度实时预览
日间 / 暗黑 Tab
原文气泡
  接收文字颜色
  接收气泡颜色
  发送文字颜色
  发送气泡颜色
会话背景
  选择或更换图片
  移除图片
输入区域
  背景色
  提示文字
消息信息
  SIM 信息开关
  文字缩放
恢复当前主题
恢复全部
```

`SettingsScreen` 新增“显示与外观”分类及“会话详情显示”入口。全局时间格式继续由原设置项管理，不在该页面重复出现。

## 草稿、保存与退出

专用 ViewModel 维护持久化配置和页面草稿：

```kotlin
data class ConversationDetailCustomizationUiState(
    val persistedTheme: ThemeConfiguration,
    val draftTheme: ThemeConfiguration,
    val previewMode: ThemeMode,
    val hasUnsavedChanges: Boolean,
    val isSaving: Boolean,
    val highTextContrastEnabled: Boolean,
)
```

规则：

- 所有调整只更新草稿和实时预览。
- 真实会话继续消费持久化配置。
- 无保存按钮以外的隐式持久化。
- 保存按钮无修改时禁用，保存中阻止重复操作和返回。
- 保存成功后刷新持久化基准并清除 dirty 状态。
- 保存失败时保留草稿。
- 恢复当前主题和恢复全部也只修改草稿，必须保存后生效。

存在未保存修改时，顶部返回和系统返回均弹出：

```text
有未保存的修改

保存后会应用到会话详情页。是否保存本次修改？

[继续编辑] [不保存] [保存并退出]
```

- 继续编辑：关闭弹窗。
- 不保存：丢弃草稿、清理草稿图片并退出。
- 保存并退出：保存成功后退出；失败则留在页面。

## 实时预览

从现有 `MockMessageScreen` 抽取可复用的预览内容，至少展示：

- 一条较长的接收原文短信。
- 一条发送原文短信。
- 时间和 SIM 标签。
- 底部输入框及 placeholder。
- 当前草稿主题的背景图片。

预览内部独立应用所选日间或暗黑 `ColorScheme`，所以系统当前处于日间时也能准确预览暗黑配置。

完整 Mock 页面继续复用相同消息工厂或渲染组件。预览与真实会话共用 resolver，不复制样式逻辑。预览中的时间格式只读取全局 `messageTimeDisplayFormat`。

## 颜色选择器

点击颜色项打开 Bottom Sheet，包含：

1. 使用官方默认。
2. Material 3 角色列表。
3. HSV 色相、饱和度和明度选择器。
4. HEX 输入。
5. 当前颜色组合预览。
6. WCAG 对比度提示。

HEX 支持：

- `#RRGGBB`
- `#AARRGGBB`

HEX 在存储前统一为 `#AARRGGBB`。低对比度只警告，不阻止保存。

Material 3 角色采用明确白名单和稳定存储名，未知角色按默认值处理。

## 背景图片处理

使用 `ActivityResultContracts.PickVisualMedia`。选中图片后不长期依赖外部 URI，而是立即处理为草稿资源。

处理流程：

1. 在 IO Dispatcher 读取图片头。
2. 使用 `ImageDecoder` 处理方向并按目标尺寸采样。
3. 最大边限制为 2160 px。
4. 压缩为 WebP 写入 `cacheDir/theme_drafts/<session>/`。
5. 预览读取草稿图片。
6. 点击保存后生成唯一 `assetId` 并复制到 `filesDir/theme_assets/`。
7. 主题 JSON 成功指向新资源后才删除旧资源。
8. 放弃修改或 ViewModel 清理时删除草稿图片。

不提供背景图片统一透明度配置，图片按其原始不透明度绘制。

## 保存事务

点击保存时：

1. 校验颜色引用、HEX、placeholder 和文字缩放范围。
2. 将本次使用的草稿图片提交为具有唯一 ID 的正式资源。
3. 构建引用正式资源 ID 的最终 `ThemeConfiguration`。
4. 使用 `SharedPreferences.commit()` 保存完整主题 JSON。
5. 成功后更新 Repository 的 `StateFlow`。
6. 删除不再被配置引用的旧图片和所有草稿文件。
7. 失败时删除本次新生成的正式文件，旧配置和旧图片保持不变。

主题配置和图片是本次保存事务的唯一内容，不涉及 `AppSettingsRepository`。

## 校验与容错

读取、保存及未来导入共用以下校验原则：

- `schemaVersion` 必须受支持。
- 颜色类型和值必须匹配。
- ARGB 必须是合法 32 位颜色。
- MD3 角色必须位于白名单。
- 文字缩放限制在现有允许范围内。
- placeholder 去除首尾多余空白并限制合理长度。
- 图片只能通过逻辑资源 ID 解析到主题私有目录。
- Moshi 忽略未知字段，以支持新增非关键字段。

单字段无效时只回退该字段。完整 JSON 无法解析时加载官方默认主题并记录英文小写日志，不使应用崩溃。

其他错误处理：

- 图片格式不支持或解码失败：不替换当前草稿图片并显示 Snackbar。
- 草稿图片丢失：预览回退默认背景并提示重新选择。
- 正式图片丢失：真实页面回退默认背景，配置页允许重新选择。
- 保存失败：保留页面草稿并允许重试。
- 系统高对比度变化：立即重新解析，不修改配置。
- 应用进程在编辑期间结束：未保存草稿不恢复，下次启动清理过期缓存。

## 预计文件边界

新增文件可能包括：

```text
app/src/main/java/vip/mystery0/pixel/text/domain/theme/
  ThemeConfiguration.kt
  ThemeConfigurationRepository.kt

app/src/main/java/vip/mystery0/pixel/text/data/repository/
  ThemeConfigurationRepositoryImpl.kt
  ThemeAssetRepositoryImpl.kt

app/src/main/java/vip/mystery0/pixel/text/ui/theme/
  ConversationDetailStyleResolver.kt
  HighTextContrastMonitor.kt

app/src/main/java/vip/mystery0/pixel/text/ui/screen/
  ConversationDetailCustomizationScreen.kt

app/src/main/java/vip/mystery0/pixel/text/viewmodel/
  ConversationDetailCustomizationViewModel.kt

app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/
  ThemeColorPickerSheet.kt
  ConversationDetailPreview.kt
```

预计修改：

```text
app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt
app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt
app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailScreen.kt
app/src/main/java/vip/mystery0/pixel/text/ui/message/MessageItem.kt
app/src/main/java/vip/mystery0/pixel/text/ui/message/cards/OriginalTextCard.kt
app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageScreen.kt
app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt
app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt
```

实现时按现有包边界适当合并，避免为简单类型创建过多零散文件。

## 验证

不新增单元测试。首先执行：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

在标记任务完成前，还必须使用用户已启动的模拟器和 `adb` 实际验证：

- 安装并启动 Debug APK。
- 进入设置和会话详情自定义页面。
- 验证日间、暗黑两套草稿。
- 验证接收、发送文字色和气泡色。
- 验证 MD3 角色、HSV、HEX 和低对比度提示。
- 验证输入区背景、placeholder、SIM 开关和文字缩放。
- 验证日间、暗黑背景图片选择、更换、移除。
- 验证调整只影响预览，保存前不影响真实会话。
- 验证保存后真实会话立即生效。
- 验证未保存退出的继续编辑、不保存、保存并退出。
- 验证恢复当前主题和恢复全部需要保存才生效。
- 重启应用后验证配置持久化。
- 验证智能卡片配色不受影响。
- 截取关键页面截图并检查 `adb logcat` 中的崩溃和相关异常。

环境允许时额外验证：

- 系统高对比度开关变化。
- Dynamic Color 壁纸变化。

普通模拟器通常无法真实覆盖双卡行为，因此至少验证 Mock 预览和单卡页面中的 SIM 显示开关；真实双卡仍列为真机残余验证项。无法由当前模拟器覆盖的内容必须在最终报告中明确列出。

## 完成标准

只有满足以下条件后才能标记实现完成：

- 功能符合本设计及 Issue #9 的本期范围。
- 编译通过。
- Lint 无新增严重问题。
- 已完成模拟器交互验证并记录证据。
- 未覆盖的真机场景已明确列为残余风险。
- 智能卡片现有配色和全局时间格式行为未被破坏。
