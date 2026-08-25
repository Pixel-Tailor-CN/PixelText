# 会话详情主题自定义 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为会话详情页增加可版本化的日间/暗黑主题配置、草稿预览、显式保存、背景图片和高对比度回退，并为未来全局主题管理与导入导出保留稳定扩展边界。

**Architecture:** 新增独立 `ThemeConfigurationRepository`，用单个 Moshi JSON 保存在 `theme_configuration` SharedPreferences；图片资源由独立 `ThemeAssetRepository` 管理。真实页面和设置预览通过同一个 `ConversationDetailStyleResolver` 将原始配置解析为 Compose 样式，设置页只编辑草稿，点击保存后才提交 JSON 与图片资源。

**Tech Stack:** Kotlin 2.4、Jetpack Compose、Material 3、Koin、Coroutines/StateFlow、Moshi、SharedPreferences、Android Photo Picker、ImageDecoder、ADB。

**Spec:** `docs/plans/2026-08-24-conversation-detail-theme-customization-design.md`

## Global Constraints

- Android 最低 SDK 31，Compile/Target SDK 37，JVM target 21。
- UI 只能使用 Jetpack Compose，不新增 XML layout。
- 不新增单元测试、`app/src/test/`、`app/src/androidTest/` 或测试依赖。
- 验证使用编译、Lint、Mock/预览、模拟器和 `adb`。
- 主题配置必须使用独立 SharedPreferences：`theme_configuration`。
- 全局 `messageTimeDisplayFormat` 不进入会话详情自定义配置或页面。
- 智能卡片本期保持现有配色，不接收主题样式。
- 背景图片不提供统一透明度。
- 日间/暗黑背景图片独立，只覆盖消息列表区域。
- 用户修改只更新草稿；必须点击“保存”才影响真实页面。
- 系统高对比度文字模式优先于颜色和背景图片自定义。
- 日志使用英文、小写开头、短语形式且不以句号结尾。
- 不引入新的网络能力或图片加载依赖。

---

## File Structure

### New files

- `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeConfiguration.kt`：可序列化主题 DTO、主题模式、颜色与图片引用、校验常量。
- `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeConfigurationRepository.kt`：主题配置 Repository 接口。
- `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeAssetRepository.kt`：草稿和正式图片资源接口。
- `app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeConfigurationRepositoryImpl.kt`：独立 SharedPreferences、Moshi JSON、迁移、StateFlow。
- `app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeAssetRepositoryImpl.kt`：Photo Picker URI 解码、采样、WebP、草稿和正式资源生命周期。
- `app/src/main/java/vip/mystery0/pixel/text/ui/theme/ConversationDetailStyle.kt`：有效样式模型、MD3 角色解析、对比度工具。
- `app/src/main/java/vip/mystery0/pixel/text/ui/theme/HighTextContrastMonitor.kt`：系统高对比度状态监听。
- `app/src/main/java/vip/mystery0/pixel/text/ui/theme/ThemeBackgroundImage.kt`：无第三方依赖的私有图片异步加载与绘制。
- `app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailCustomizationViewModel.kt`：草稿、保存、恢复、图片与退出状态。
- `app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ThemeColorPickerSheet.kt`：默认、MD3 角色、HSV、HEX、对比度提示。
- `app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ConversationDetailPreview.kt`：可复用会话预览。
- `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailCustomizationScreen.kt`：完整配置页面。

### Modified files

- `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`：移除已迁移的会话文字缩放字段/API，保留时间格式。
- `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`：移除会话文字缩放读写。
- `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`：注册 Repository、Monitor 和 ViewModel。
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/cards/OriginalTextCard.kt`：接收显式原文样式。
- `app/src/main/java/vip/mystery0/pixel/text/ui/message/MessageItem.kt`：按收发方向选择样式并控制 SIM 标签。
- `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailScreen.kt`：消费主题配置、绘制列表背景、设置输入区样式、迁移缩放手势写入。
- `app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageFactory.kt`：支持构造接收和发送 Mock 消息。
- `app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageScreen.kt`：复用预览组件。
- `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`：增加“显示与外观”入口。
- `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`：增加配置页路由。

---

### Task 1: 建立版本化主题模型、独立存储和旧缩放迁移

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeConfiguration.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeConfigurationRepository.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeConfigurationRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Produces: `ThemeConfiguration`, `ThemeMode`, `MaterialColorRole`, `ThemeColorReference`, `ThemeImageReference`, `ThemeConfigurationRepository.configuration`, `save()`, `update()`。
- Consumes later: Tasks 2–8。

- [ ] **Step 1: 定义可序列化主题模型和稳定存储值**

在 `ThemeConfiguration.kt` 使用 Moshi codegen，字段使用默认值保证向前兼容：

```kotlin
@JsonClass(generateAdapter = true)
data class ThemeConfiguration(
    val schemaVersion: Int = CURRENT_THEME_SCHEMA_VERSION,
    val conversationDetail: ConversationDetailThemeModule =
        ConversationDetailThemeModule(),
)

@JsonClass(generateAdapter = true)
data class ConversationDetailThemeModule(
    val light: ConversationDetailAppearance = ConversationDetailAppearance(),
    val dark: ConversationDetailAppearance = ConversationDetailAppearance(),
    val showSimInfo: Boolean = true,
    val inputPlaceholder: String = DEFAULT_CONVERSATION_INPUT_PLACEHOLDER,
    val textScale: Float = DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE,
)

@JsonClass(generateAdapter = true)
data class ConversationDetailAppearance(
    val receivedTextColor: ThemeColorReference? = null,
    val sentTextColor: ThemeColorReference? = null,
    val receivedBubbleColor: ThemeColorReference? = null,
    val sentBubbleColor: ThemeColorReference? = null,
    val inputBackgroundColor: ThemeColorReference? = null,
    val backgroundImage: ThemeImageReference? = null,
)

@JsonClass(generateAdapter = true)
data class ThemeColorReference(
    val type: ThemeColorType,
    val value: String,
)

@JsonClass(generateAdapter = true)
data class ThemeImageReference(val assetId: String)

enum class ThemeMode { LIGHT, DARK }
enum class ThemeColorType { MATERIAL_ROLE, CUSTOM_ARGB }

enum class MaterialColorRole(val storageValue: String) {
    PRIMARY("primary"),
    ON_PRIMARY("on_primary"),
    PRIMARY_CONTAINER("primary_container"),
    ON_PRIMARY_CONTAINER("on_primary_container"),
    SECONDARY("secondary"),
    ON_SECONDARY("on_secondary"),
    SECONDARY_CONTAINER("secondary_container"),
    ON_SECONDARY_CONTAINER("on_secondary_container"),
    TERTIARY("tertiary"),
    ON_TERTIARY("on_tertiary"),
    TERTIARY_CONTAINER("tertiary_container"),
    ON_TERTIARY_CONTAINER("on_tertiary_container"),
    SURFACE("surface"),
    ON_SURFACE("on_surface"),
    SURFACE_VARIANT("surface_variant"),
    ON_SURFACE_VARIANT("on_surface_variant"),
    ERROR("error"),
    ON_ERROR("on_error"),
    ERROR_CONTAINER("error_container"),
    ON_ERROR_CONTAINER("on_error_container"),
    INVERSE_SURFACE("inverse_surface"),
    INVERSE_ON_SURFACE("inverse_on_surface"),
}

const val CURRENT_THEME_SCHEMA_VERSION = 1
const val DEFAULT_CONVERSATION_INPUT_PLACEHOLDER = "请输入"
const val DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE = 1f
const val MIN_CONVERSATION_DETAIL_TEXT_SCALE = 0.85f
const val MAX_CONVERSATION_DETAIL_TEXT_SCALE = 1.8f
const val MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH = 40
```

增加：

```kotlin
fun ThemeConfiguration.normalized(): ThemeConfiguration
fun ConversationDetailThemeModule.appearance(mode: ThemeMode): ConversationDetailAppearance
fun ConversationDetailThemeModule.withAppearance(
    mode: ThemeMode,
    appearance: ConversationDetailAppearance,
): ConversationDetailThemeModule
```

`normalized()` 必须限制缩放、trim placeholder、限制 40 字符、过滤空 `assetId` 和非法颜色引用。

- [ ] **Step 2: 定义 Repository 接口**

```kotlin
interface ThemeConfigurationRepository {
    val configuration: StateFlow<ThemeConfiguration>
    suspend fun save(configuration: ThemeConfiguration): Result<Unit>
    suspend fun update(
        transform: (ThemeConfiguration) -> ThemeConfiguration,
    ): Result<Unit>
}
```

不要在接口中暴露 SharedPreferences、Moshi 或文件路径。

- [ ] **Step 3: 实现独立 SharedPreferences 和 Moshi JSON**

`ThemeConfigurationRepositoryImpl` 使用：

```kotlin
private const val THEME_PREFS_NAME = "theme_configuration"
private const val KEY_CURRENT_THEME = "current_theme"
private const val LEGACY_APP_PREFS_NAME = "app_settings"
private const val LEGACY_TEXT_SCALE_KEY = "conversation_detail_text_scale"
```

初始化流程：

```kotlin
private val adapter = Moshi.Builder().build()
    .adapter(ThemeConfiguration::class.java)
private val _configuration = MutableStateFlow(readOrMigrate())
override val configuration = _configuration.asStateFlow()
```

`readOrMigrate()`：

1. 已有 JSON 时解析；`schemaVersion != CURRENT_THEME_SCHEMA_VERSION` 时输出 `theme config version unsupported version=...` 并回退默认配置，否则执行 `normalized()`。
2. JSON 损坏时输出 `theme config parse failed error=...`，返回默认配置。
3. 没有 JSON 时从旧 `app_settings` 读取缩放。
4. 使用 `commit()` 写入初始 JSON；成功后删除旧键。
5. 初始写入失败时仍返回带旧缩放的内存配置，不删除旧键。

`save()` 在 `Dispatchers.IO` 中执行 `commit()`，成功后再更新 `_configuration.value`。使用 `Mutex` 串行化 `save()` 和 `update()`；`update()` 必须在锁内读取最新 `_configuration.value`、执行 transform、标准化并提交，避免会话捏合缩放覆盖设置页面刚保存的其他字段。

- [ ] **Step 4: 注册 Koin 依赖**

在 `AppModule.kt` 增加：

```kotlin
single<ThemeConfigurationRepository> {
    ThemeConfigurationRepositoryImpl(androidContext())
}
```

- [ ] **Step 5: 编译验证基础模型与 Moshi codegen**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`，无 Moshi adapter 或 KSP 错误。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/theme \
  app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeConfigurationRepositoryImpl.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: 添加版本化主题配置存储"
```

---

### Task 2: 实现主题图片草稿和正式资源生命周期

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeAssetRepository.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeAssetRepositoryImpl.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Consumes: `ThemeMode`, `ThemeImageReference` from Task 1。
- Produces: `ThemeImageDraft`, `createDraftBackground()`, `commitDraft()`, `discardDraft()`, `deleteAsset()`, `resolve()`。

- [ ] **Step 1: 定义资源接口和草稿模型**

```kotlin
data class ThemeImageDraft(
    val draftId: String,
    val mode: ThemeMode,
)

interface ThemeAssetRepository {
    suspend fun createDraftBackground(
        mode: ThemeMode,
        sourceUri: String,
    ): Result<ThemeImageDraft>

    suspend fun commitDraft(draft: ThemeImageDraft): Result<ThemeImageReference>
    fun discardDraft(draft: ThemeImageDraft)
    fun deleteAsset(reference: ThemeImageReference)
    fun resolve(reference: ThemeImageReference): File?
    fun resolve(draft: ThemeImageDraft): File?
    suspend fun cleanStaleDrafts()
}
```

接口使用 URI 字符串，避免 domain 接口依赖 Android `Uri`。

- [ ] **Step 2: 实现安全目录和 ID 校验**

正式目录：`filesDir/theme_assets/`；草稿目录：`cacheDir/theme_drafts/`。

只允许：

```kotlin
private val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
```

`resolve()` 必须验证 ID，构造 canonical path，并确认结果仍位于预期目录内。禁止接受路径分隔符和 `..`。

- [ ] **Step 3: 实现采样、方向修正和 WebP 压缩**

在 `Dispatchers.IO` 中使用 `ImageDecoder`：

```kotlin
val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(sourceUri))
val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val maxEdge = maxOf(info.size.width, info.size.height)
    val sample = (maxEdge / MAX_BACKGROUND_EDGE_PX)
        .coerceAtLeast(1)
    decoder.setTargetSampleSize(sample)
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
}
```

如果采样后最大边仍超过 2160 px，再使用 `Bitmap.createScaledBitmap()` 精确限制。输出：

```kotlin
bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, output)
```

常量：

```kotlin
private const val MAX_BACKGROUND_EDGE_PX = 2160
private const val WEBP_QUALITY = 88
private const val STALE_DRAFT_AGE_MILLIS = 24L * 60 * 60 * 1000
```

失败日志示例：`theme image decode failed uri=... error=...`。

- [ ] **Step 4: 实现草稿提交和清理**

- 草稿文件名：`draft_<uuid>.webp`。
- 正式 ID：`conversation_detail_<light|dark>_<uuid>`。
- `commitDraft()` 先复制到正式临时文件，再原子 rename；成功后返回 `ThemeImageReference`，但在主题 JSON 提交成功前保留原草稿，以便保存失败后重试。
- `discardDraft()` 只删除草稿；删除单个私有缓存文件是同步轻量操作，便于退出和 `onCleared()` 可靠清理。
- `deleteAsset()` 只删除校验通过的正式文件。
- `cleanStaleDrafts()` 删除超过 24 小时的缓存。

- [ ] **Step 5: 注册 Koin 并在启动时异步清理旧草稿**

```kotlin
single<ThemeAssetRepository> { ThemeAssetRepositoryImpl(androidContext()) }
```

不要阻塞 Koin 初始化；由后续 ViewModel 初始化或应用已有协程入口调用 `cleanStaleDrafts()`。

- [ ] **Step 6: 编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/theme/ThemeAssetRepository.kt \
  app/src/main/java/vip/mystery0/pixel/text/data/repository/ThemeAssetRepositoryImpl.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: 添加主题背景资源管理"
```

---

### Task 3: 实现统一样式解析、高对比度监听和背景图片加载

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/theme/ConversationDetailStyle.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/theme/HighTextContrastMonitor.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/theme/ThemeBackgroundImage.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Consumes: Task 1 theme models; Task 2 asset resolver。
- Produces: `ResolvedConversationDetailStyle`, `resolveConversationDetailStyle()`, `HighTextContrastMonitor.enabled`, `ThemeBackgroundImage()`。

- [ ] **Step 1: 定义有效样式模型**

```kotlin
@Immutable
data class ResolvedOriginalMessageStyle(
    val receivedTextColor: Color,
    val sentTextColor: Color,
    val receivedBubbleColor: Color,
    val sentBubbleColor: Color,
)

@Immutable
data class ResolvedInputAreaStyle(val backgroundColor: Color)

@Immutable
data class ResolvedConversationBackgroundStyle(
    val image: ThemeImageReference?,
)

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

- [ ] **Step 2: 实现 MD3 角色解析**

使用 Task 1 定义的 `MaterialColorRole` 稳定白名单，在 UI 层实现 Compose `ColorScheme` 映射：

```kotlin
fun MaterialColorRole.resolve(scheme: ColorScheme): Color
fun ThemeColorReference?.resolveOr(
    scheme: ColorScheme,
    fallback: Color,
): Color
```

自定义色解析必须支持标准化 `#AARRGGBB`，非法值回退 `fallback`。

- [ ] **Step 3: 实现页面级 resolver**

```kotlin
fun resolveConversationDetailStyle(
    configuration: ThemeConfiguration,
    mode: ThemeMode,
    colorScheme: ColorScheme,
    highTextContrastEnabled: Boolean,
): ResolvedConversationDetailStyle
```

普通模式默认：

```kotlin
text = colorScheme.onSurface
bubble = colorScheme.surfaceVariant
input = colorScheme.surfaceVariant.copy(alpha = 0.5f)
```

高对比度模式：忽略所有自定义颜色和背景图片；文字使用 `onSurface`，气泡和输入区使用 `surface`，保留 SIM、placeholder 和 textScale。

- [ ] **Step 4: 实现对比度工具**

提供：

```kotlin
fun contrastRatio(foreground: Color, background: Color): Float
fun readableLinkColor(
    preferred: Color,
    textColor: Color,
    backgroundColor: Color,
): Color
```

`readableLinkColor()` 在 `primary` 对气泡对比度小于 `3f` 时回退正文颜色。

- [ ] **Step 5: 实现高对比度状态监听**

`HighTextContrastMonitor` 构造时读取 `AccessibilityManager.isHighTextContrastEnabled`，注册 `AccessibilityManager.HighTextContrastChangeListener` 并暴露 `StateFlow<Boolean>`：

```kotlin
class HighTextContrastMonitor(context: Context) {
    val enabled: StateFlow<Boolean>
}
```

使用 application context；监听器生命周期与 Koin single 一致。

- [ ] **Step 6: 实现无第三方依赖图片加载组件**

```kotlin
@Composable
fun ThemeBackgroundImage(
    file: File?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
)
```

使用 `produceState` + `withContext(Dispatchers.IO)` + `BitmapFactory.decodeFile()`，转换为 `ImageBitmap` 后用：

```kotlin
Image(
    bitmap = bitmap,
    contentDescription = contentDescription,
    contentScale = ContentScale.Crop,
    modifier = modifier,
)
```

文件为空或解码失败时不绘制，不阻塞 UI。

- [ ] **Step 7: 注册 Monitor**

```kotlin
single { HighTextContrastMonitor(androidContext()) }
```

- [ ] **Step 8: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/ui/theme \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: 添加会话主题样式解析"
```

---

### Task 4: 将有效样式接入真实会话并移除旧缩放数据源

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/cards/OriginalTextCard.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/MessageItem.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`

**Interfaces:**
- Consumes: `ResolvedConversationDetailStyle`, `ThemeConfigurationRepository`, `ThemeAssetRepository`, `HighTextContrastMonitor`。
- Produces: 真实会话完整主题渲染；Tasks 5/7 复用新的 MessageItem 参数。

- [ ] **Step 1: 修改 OriginalTextCard 接口**

改为：

```kotlin
@Composable
fun OriginalTextCard(
    content: String,
    isSelected: Boolean = false,
    subject: String? = null,
    textScale: Float = 1f,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
)
```

动画目标：

```kotlin
val resolvedBackground = if (isSelected) inverseSurface else backgroundColor
val resolvedText = if (isSelected) inverseOnSurface else textColor
val preferredLink = if (isSelected) inversePrimary else primary
val resolvedLink = readableLinkColor(preferredLink, resolvedText, resolvedBackground)
```

本任务初始实现不向智能卡片传递主题配置。后续背景图片可读性修复允许统一修改智能卡片主容器：将少量现有强调色预合成到 Material 3 `surfaceContainer`，生成最终不透明背景；不得改变卡片强调色、内部层级、选中态或向 `MessageCardFactory` 增加主题参数。

- [ ] **Step 2: 修改 MessageItem 接口和 SIM 开关**

增加：

```kotlin
originalMessageStyle: ResolvedOriginalMessageStyle,
showSimInfo: Boolean = true,
```

按方向解析一次：

```kotlin
val bubbleColor = if (message.isReceived) {
    originalMessageStyle.receivedBubbleColor
} else {
    originalMessageStyle.sentBubbleColor
}
val textColor = if (message.isReceived) {
    originalMessageStyle.receivedTextColor
} else {
    originalMessageStyle.sentTextColor
}
```

所有 `OriginalTextCard` 调用传入这两个颜色。SIM `Surface` 整块放入：

```kotlin
if (showSimInfo) { ... }
```

- [ ] **Step 3: 在 ConversationDetailScreen 订阅并解析主题**

注入：

```kotlin
themeRepository: ThemeConfigurationRepository = koinInject(),
themeAssetRepository: ThemeAssetRepository = koinInject(),
highTextContrastMonitor: HighTextContrastMonitor = koinInject(),
```

收集状态并解析：

```kotlin
val themeConfiguration by themeRepository.configuration.collectAsState()
val highTextContrast by highTextContrastMonitor.enabled.collectAsState()
val mode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT
val detailStyle = resolveConversationDetailStyle(
    themeConfiguration,
    mode,
    MaterialTheme.colorScheme,
    highTextContrast,
)
```

- [ ] **Step 4: 将缩放手势改为主题 Repository 写入**

本地 `textScale` 使用 `detailStyle.textScale` 初始化，并在持久化主题变化后同步。捏合缩放仍立即生效，因为它是会话详情现有交互，不属于设置页草稿：

```kotlin
val coroutineScope = rememberCoroutineScope()
// pointerInput 手势循环结束后：
coroutineScope.launch {
    themeRepository.update { latest ->
        latest.copy(
            conversationDetail = latest.conversationDetail.copy(
                textScale = gestureScale,
            )
        )
    }
}
```

手势期间只更新本地值，手势结束时保存最终缩放，避免每帧写 Preferences。只有实际发生缩放时才调用 `update()`。

- [ ] **Step 5: 接入输入区颜色和 placeholder**

将底部 `Surface.color` 替换为 `detailStyle.inputArea.backgroundColor`，placeholder 文本替换为：

```kotlin
text = detailStyle.inputPlaceholder
```

输入正文颜色仍使用系统 `onSurface`，本期不开放配置。

- [ ] **Step 6: 只在消息列表区域绘制背景图**

用 `Box` 包住现有消息区域：

```kotlin
Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
    ThemeBackgroundImage(
        file = detailStyle.background.image
            ?.let(themeAssetRepository::resolve),
        modifier = Modifier.matchParentSize(),
    )
    LazyColumn(...)
}
```

Loading/Error 页面不必显示用户背景；TopAppBar 和 bottomBar 保持独立。

- [ ] **Step 7: 保持全局时间格式和智能卡片业务配色不变**

`MessageItem.timeDisplayFormat` 继续传：

```kotlin
timeDisplayFormat = appSettings.messageTimeDisplayFormat
```

`MessageCardFactory.CreateCard` 不新增样式参数。智能卡片继续自行使用现有强调色；主容器背景统一为强调色与 `surfaceContainer` 预合成后的不透明颜色，以保证会话背景图片上的可读性。

- [ ] **Step 8: 更新现有 MockMessageScreen 调用保证本任务可独立编译**

使用默认 `ThemeConfiguration()`、当前 `MaterialTheme.colorScheme` 和当前系统深浅模式解析 `ResolvedOriginalMessageStyle`，向现有 `MessageItem` 调用补充 `originalMessageStyle` 与 `showSimInfo`。Task 5 再将这里重构为共享预览组件。

- [ ] **Step 9: 删除 AppSettings 中旧文字缩放字段和 API**

从 `AppSettings`、`AppSettingsRepository`、`AppSettingsKeys` 和实现中删除：

- `conversationDetailTextScale`
- `setConversationDetailTextScale()`
- `getConversationDetailTextScale()`
- `KEY_CONVERSATION_DETAIL_TEXT_SCALE`
- `DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE`

迁移实现使用 Task 1 内部 legacy 字符串，不依赖这些常量。

- [ ] **Step 10: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/ui/message \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailScreen.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageScreen.kt \
  app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt \
  app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt
git commit -m "feat: 应用会话详情主题样式"
```

---

### Task 5: 抽取可复用实时预览并更新 Mock 数据

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ConversationDetailPreview.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageFactory.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock/MockMessageScreen.kt`

**Interfaces:**
- Consumes: Task 3 style resolver；Task 4 MessageItem 参数。
- Produces: `ConversationDetailPreview()` for Task 7。

- [ ] **Step 1: 扩展 MockMessageFactory 支持方向**

增加：

```kotlin
data class MockMessageSpec(
    val content: String,
    val isReceived: Boolean = true,
    val simName: String = "卡1",
)

fun createSpecs(
    specs: List<MockMessageSpec>,
    now: Long = System.currentTimeMillis(),
): List<MessageModel>
```

`create(messages: List<String>)` 保留并委托给 `createSpecs()`，避免破坏完整 Mock 页面。

- [ ] **Step 2: 实现固定高度预览**

```kotlin
@Composable
fun ConversationDetailPreview(
    messages: List<MessageModel>,
    style: ResolvedConversationDetailStyle,
    timeDisplayFormat: MessageTimeDisplayFormat,
    backgroundFile: File?,
    modifier: Modifier = Modifier,
)
```

预览内容包含一条长接收原文、一条发送原文、时间、SIM 和输入区域。使用 `Box` 绘制背景，再使用不可交互 `LazyColumn`/`Column`：

```kotlin
MessageItem(
    message = message,
    isSelected = false,
    textScale = style.textScale,
    originalMessageStyle = style.originalMessage,
    showSimInfo = style.showSimInfo,
    interactionEnabled = false,
    timeDisplayFormat = timeDisplayFormat,
    onClick = {},
    onLongClick = {},
)
```

底部预览输入框必须使用 `style.inputArea.backgroundColor` 和 `style.inputPlaceholder`。

- [ ] **Step 3: 支持预览独立深浅主题**

在设置页面调用处使用独立 `MaterialExpressiveTheme` 包裹预览。为减少重复，在预览文件提供：

```kotlin
@Composable
fun ConversationDetailPreviewTheme(
    mode: ThemeMode,
    content: @Composable (ColorScheme) -> Unit,
)
```

内部使用当前 context 的 `dynamicLightColorScheme()` / `dynamicDarkColorScheme()`。

- [ ] **Step 4: 改造 MockMessageScreen 复用预览核心渲染**

完整 Mock 页面仍可展示原消息列表，但至少复用 `MockMessageSpec` 和 Task 4 新样式接口；默认解析使用当前 `MaterialTheme.colorScheme` 和默认 `ThemeConfiguration()`。

- [ ] **Step 5: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ConversationDetailPreview.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/mock
git commit -m "feat: 添加会话主题实时预览"
```

---

### Task 6: 实现配置草稿 ViewModel 和保存事务

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailCustomizationViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**Interfaces:**
- Consumes: Tasks 1–3 Repositories/Monitor。
- Produces: `ConversationDetailCustomizationUiState` and all screen callbacks for Task 7。

- [ ] **Step 1: 定义页面状态和消息事件**

```kotlin
data class ConversationDetailCustomizationUiState(
    val persistedTheme: ThemeConfiguration = ThemeConfiguration(),
    val draftTheme: ThemeConfiguration = ThemeConfiguration(),
    val previewMode: ThemeMode = ThemeMode.LIGHT,
    val highTextContrastEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)

sealed interface ThemeCustomizationEvent {
    data class Message(val text: String) : ThemeCustomizationEvent
    data object Saved : ThemeCustomizationEvent
    data object SavedAndExit : ThemeCustomizationEvent
}
```

`hasUnsavedChanges` 必须由 persisted/draft 比较得出，不单独手动维护真值。

- [ ] **Step 2: 初始化持久化配置和高对比度状态**

组合两个 StateFlow；初始化时调用 `themeAssetRepository.cleanStaleDrafts()`。当 Repository 发生外部变化且页面没有 dirty 状态时同步刷新草稿。

- [ ] **Step 3: 提供明确的草稿更新方法**

至少包含：

```kotlin
fun setPreviewMode(mode: ThemeMode)
fun setReceivedTextColor(reference: ThemeColorReference?)
fun setSentTextColor(reference: ThemeColorReference?)
fun setReceivedBubbleColor(reference: ThemeColorReference?)
fun setSentBubbleColor(reference: ThemeColorReference?)
fun setInputBackgroundColor(reference: ThemeColorReference?)
fun setShowSimInfo(show: Boolean)
fun setInputPlaceholder(value: String)
fun setTextScale(value: Float)
fun removeBackground(mode: ThemeMode)
fun resetCurrentMode()
fun resetAll()
```

`resetCurrentMode()` 只把当前 `light` 或 `dark` appearance 恢复默认，不重置全局 SIM、placeholder、textScale。`resetAll()` 恢复完整 `ThemeConfiguration()`。

- [ ] **Step 4: 实现图片草稿替换**

```kotlin
fun selectBackground(sourceUri: String)
```

流程：

1. 为当前 `previewMode` 创建新草稿。
2. 成功后丢弃该模式旧草稿。
3. 将 draft 映射为仅供 UI 使用的临时 `ThemeImageReference("draft:<draftId>")`。
4. Resolver 保留引用；页面解析背景文件时先识别 `draft:` 并调用 `resolve(draft)`。
5. `removeBackground()` 将配置设为 null 并丢弃未使用草稿。

维护：

```kotlin
private val imageDrafts = mutableMapOf<ThemeMode, ThemeImageDraft>()
fun resolvePreviewBackground(mode: ThemeMode): File?
```

`resolvePreviewBackground()` 先返回该模式当前草稿文件；没有草稿时再解析 `draftTheme` 中的正式 `ThemeImageReference`。`draftTheme` 或草稿映射变化时必须发布新 `UiState`，保证预览重组。

- [ ] **Step 5: 实现保存事务**

```kotlin
fun save(exitAfterSave: Boolean = false)
```

在 `viewModelScope.launch` 中：

1. `isSaving = true`。
2. 对 draft 配置中的每个 `draft:` 引用调用 `commitDraft()`。
3. 构建只包含正式 asset ID 的 `finalConfiguration.normalized()`。
4. 调用 `themeRepository.save(finalConfiguration)`。
5. 保存成功后删除 persisted 配置中已不再引用的旧正式图片。
6. 清空草稿映射并发出 `Saved` 或 `SavedAndExit`。
7. 保存失败时删除本次刚提交的正式文件，保留原草稿配置和可重试状态。
8. 最终 `isSaving = false`。

收集引用的工具函数：

```kotlin
private fun ThemeConfiguration.backgroundReferences(): Set<ThemeImageReference>
```

- [ ] **Step 6: 实现放弃修改清理**

```kotlin
fun discardChanges()
```

删除所有草稿文件，将 `draftTheme` 恢复 `persistedTheme`。`onCleared()` 同步调用 `discardDraft()` 清理仍未提交的少量私有草稿文件，不能依赖已经取消的 `viewModelScope`。

- [ ] **Step 7: 注册 Koin ViewModel**

```kotlin
viewModel {
    ConversationDetailCustomizationViewModel(get(), get(), get())
}
```

- [ ] **Step 8: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailCustomizationViewModel.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: 添加会话主题配置草稿状态"
```

---

### Task 7: 实现颜色选择器和完整配置页面

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ThemeColorPickerSheet.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailCustomizationScreen.kt`

**Interfaces:**
- Consumes: Task 3 resolver, Task 5 preview, Task 6 ViewModel。
- Produces: navigation destination screen for Task 8。

- [ ] **Step 1: 实现颜色输入标准化和 HSV 转换**

在 `ThemeColorPickerSheet.kt` 提供：

```kotlin
fun parseThemeHexColor(value: String): Color?
fun Color.toThemeHex(): String
fun Color.toHsv(): FloatArray
fun colorFromHsv(hue: Float, saturation: Float, value: Float): Color
```

规则：`#RRGGBB` 自动补 `FF` alpha，输出统一 `#AARRGGBB`；非法内容返回 null。

- [ ] **Step 2: 实现 ThemeColorPickerSheet**

接口：

```kotlin
@Composable
fun ThemeColorPickerSheet(
    title: String,
    current: ThemeColorReference?,
    colorScheme: ColorScheme,
    comparisonBackground: Color,
    onDismiss: () -> Unit,
    onConfirm: (ThemeColorReference?) -> Unit,
)
```

Sheet 必须包含：

- “使用官方默认”选项。
- `MaterialColorRole.entries` 列表和色块。
- HSV 色相条与饱和度/明度二维区域，使用 Compose `Canvas` 和 pointer input。
- HEX `OutlinedTextField`。
- 前景/背景组合预览。
- `contrastRatio()` 输出，例如 `对比度 4.8:1`。
- 小于 4.5 时显示警告，但确认按钮仍可用；HEX 非法时确认按钮禁用。

- [ ] **Step 3: 搭建配置页面 Scaffold 和返回保护**

`ConversationDetailCustomizationScreen`：

```kotlin
@Composable
fun ConversationDetailCustomizationScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConversationDetailCustomizationViewModel = koinViewModel(),
    settingsRepository: AppSettingsRepository = koinInject(),
    themeAssetRepository: ThemeAssetRepository = koinInject(),
)
```

TopAppBar：返回按钮、标题“会话详情显示”、全局“保存”。保存按钮：

```kotlin
enabled = state.hasUnsavedChanges && !state.isSaving
```

`BackHandler` 与顶部返回共同调用 `requestNavigateBack()`。dirty 时显示三按钮弹窗：继续编辑、不保存、保存并退出。

- [ ] **Step 4: 注册 Photo Picker**

```kotlin
val picker = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri ->
    uri?.let { viewModel.selectBackground(it.toString()) }
}
```

启动：

```kotlin
picker.launch(
    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
)
```

不申请相册权限。

- [ ] **Step 5: 实现固定高度实时预览和主题 Tab**

预览高度建议 `320.dp`。通过 `ConversationDetailPreviewTheme(state.previewMode)` 获取对应动态 `ColorScheme`，用草稿配置调用 resolver。时间格式从：

```kotlin
val appSettings by settingsRepository.settings.collectAsState()
```

读取 `appSettings.messageTimeDisplayFormat`，页面不显示时间格式设置项。

背景文件直接使用 `viewModel.resolvePreviewBackground(state.previewMode)`；该方法优先解析当前模式草稿，没有草稿时解析正式 `ThemeImageReference`。

- [ ] **Step 6: 实现设置分组**

使用 Material 3 `Card`、`ListItem`、`Switch`、`Slider`、`OutlinedTextField`：

- 原文气泡：接收文字、接收气泡、发送文字、发送气泡。
- 会话背景：选择/更换、移除；不显示透明度。
- 输入区域：背景色、placeholder。
- 消息信息：SIM 开关、文字缩放 `0.85f..1.8f`。
- 恢复：恢复当前主题、恢复全部；恢复全部需要二次确认。

高对比度开启时颜色和背景操作 `enabled = false`，顶部提示：`系统高对比度文字已启用，颜色和背景图片自定义暂时不会生效`。非颜色配置仍可编辑。

- [ ] **Step 7: 处理保存事件、Snackbar 和退出**

收集 `ThemeCustomizationEvent`：

- `Message` → Snackbar。
- `Saved` → Snackbar“已保存”。
- `SavedAndExit` → `onNavigateBack()`。

普通点击保存不退出；“保存并退出”成功后退出。

- [ ] **Step 8: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/ui/component/theme/ThemeColorPickerSheet.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailCustomizationScreen.kt
git commit -m "feat: 添加会话详情主题配置页面"
```

---

### Task 8: 接入设置入口和导航

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`

**Interfaces:**
- Consumes: `ConversationDetailCustomizationScreen` from Task 7。
- Produces: 用户可达的完整功能入口。

- [ ] **Step 1: 为 SettingsScreen 增加导航回调**

```kotlin
onNavigateToConversationDetailCustomization: () -> Unit = {},
```

- [ ] **Step 2: 增加“显示与外观”分类和入口**

在“应用功能”之前增加：

```kotlin
preferenceCategory(
    key = "category_display",
    title = { Text("显示与外观") },
)
item(key = "conversation_detail_customization", contentType = "Preference") {
    Preference(
        title = { Text("会话详情显示") },
        summary = { Text("自定义原文气泡、输入区域、背景图片和 SIM 信息") },
        icon = {
            Icon(Icons.Rounded.Palette, contentDescription = null)
        },
        onClick = onNavigateToConversationDetailCustomization,
    )
}
```

不要移动或复制现有“短信时间显示格式”。

- [ ] **Step 3: 增加导航路由**

在 `AppNavigation.kt` import 页面，在 settings 回调中：

```kotlin
onNavigateToConversationDetailCustomization = {
    navController.navigate("conversation_detail_customization")
}
```

并增加：

```kotlin
composable("conversation_detail_customization") {
    ConversationDetailCustomizationScreen(
        onNavigateBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 4: 编译并提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt
git commit -m "feat: 接入会话主题设置入口"
```

---

### Task 9: 全量静态验证和问题修复

**Files:**
- Modify: implementation files found by compiler/lint only。

**Interfaces:**
- Consumes: Tasks 1–8 complete feature。
- Produces: 可安装 Debug APK，无新增严重 Lint 问题。

- [ ] **Step 1: 检查范围和残留旧 API**

```bash
rg -n "conversationDetailTextScale|setConversationDetailTextScale|getConversationDetailTextScale|backgroundAlpha" app/src/main/java
```

Expected:

- 旧 AppSettings 缩放 API 无结果。
- 不存在背景透明度配置。
- `messageTimeDisplayFormat` 只在全局设置、真实消息和预览读取处出现，不在主题模型/ViewModel 的 setter 中出现。

检查智能卡片没有接入用户主题配置，并且主容器使用统一的不透明合成色：

```bash
rg -n "smartCardContainerColor" \
  app/src/main/java/vip/mystery0/pixel/text/ui/message/cards
rg -n "ResolvedConversationDetailStyle|ThemeConfiguration" \
  app/src/main/java/vip/mystery0/pixel/text/ui/message/cards \
  app/src/main/java/vip/mystery0/pixel/text/ui/message/factory
```

Expected:

- `MessageCardFactory` 覆盖的智能卡片主容器均调用 `smartCardContainerColor`。
- 智能卡片和工厂不消费用户主题配置。
- 现有强调色、内部层级和选中态配色不变。

- [ ] **Step 2: 完整编译**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行 Lint**

```bash
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`，无由本功能引入的 fatal/error。检查报告：

```text
app/build/reports/lint-results-debug.html
```

- [ ] **Step 4: 构建可安装 APK**

```bash
./gradlew assembleDebug
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: 检查 diff 和工作区**

```bash
git diff --check
git status --short
```

修复仅与本功能相关的格式、警告和编译问题，不做无关重构。

- [ ] **Step 6: 提交静态验证修复**

仅在有修复时提交：

```bash
git add <修复文件>
git commit -m "fix: 修复会话主题配置验证问题"
```

---

### Task 10: 使用已启动模拟器和 ADB 完成交互验证

**Files:**
- Do not commit simulator screenshots or temporary UI dumps unless the user explicitly asks。
- Modify implementation files only when verification finds a defect。

**Interfaces:**
- Consumes: Task 9 APK。
- Produces: 最终完成报告中的模拟器、操作路径、截图/日志证据和残余真机风险。

- [ ] **Step 1: 确认模拟器设备和系统版本**

```bash
adb devices -l
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell wm size
```

Expected: 至少一个状态为 `device` 的模拟器。记录设备名、Android 版本、API 和分辨率。

- [ ] **Step 2: 安装并冷启动 Debug 应用**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop vip.mystery0.pixel.text.debug
adb shell monkey -p vip.mystery0.pixel.text.debug -c android.intent.category.LAUNCHER 1
```

如果首次启动需要默认短信角色或权限，使用 UI dump 定位按钮：

```bash
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml build/window.xml
```

从节点 `bounds` 计算中心点后执行 `adb shell input tap X Y`。

- [ ] **Step 3: 准备 Photo Picker 测试图片**

从模拟器当前界面生成一张合法 PNG 并放入 Pictures：

```bash
adb exec-out screencap -p > build/theme-test-background.png
adb push build/theme-test-background.png /sdcard/Pictures/theme-test-background.png
adb shell am broadcast \
  -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Pictures/theme-test-background.png
```

- [ ] **Step 4: 导航到配置页面并记录初始状态**

通过 `uiautomator dump` 查找“设置”“显示与外观”“会话详情显示”，按 bounds 中心依次点击。截图：

```bash
adb exec-out screencap -p > build/theme-customization-initial.png
```

确认：

- 页面存在全局保存按钮，初始禁用。
- 有日间/暗黑 Tab。
- 预览有接收、发送原文、SIM、时间和输入区。
- 页面没有时间格式项和背景透明度项。

- [ ] **Step 5: 验证草稿与未保存退出**

使用 UI dump 定位 SIM 开关、placeholder 和文字缩放；修改后确认保存按钮启用、预览立即变化。点击返回并逐一验证：

1. “继续编辑”留在页面且草稿仍在。
2. “不保存”退出；重新进入后修改未生效。
3. 再修改并选择“保存并退出”；重新进入后配置保留。

每个关键状态截图保存在 `build/`。

- [ ] **Step 6: 验证颜色配置**

分别对接收/发送文字和气泡：

- 选择一个 MD3 角色并确认预览变化。
- 使用 HSV 选择器修改颜色。
- 输入合法 `#RRGGBB` 和 `#AARRGGBB`。
- 输入非法 HEX，确认无法确认。
- 选择低对比度组合，确认仅警告、不阻止保存。
- 切换日间/暗黑，确认配置相互独立。

保存后进入真实会话，确认原文气泡变化；智能卡片保持现有配色。

- [ ] **Step 7: 验证背景图片草稿、保存、更换和移除**

- 日间选择测试图片，确认只更新预览。
- 保存前进入真实会话，确认旧背景不变。
- 保存后确认真实消息列表显示背景，TopAppBar 和输入区不被背景覆盖。
- 暗黑模式选择另一草稿并确认不覆盖日间引用。
- 更换图片后保存，检查无崩溃。
- 移除图片但不保存，返回时选择不保存，确认原图片保留。
- 再移除并保存，确认恢复默认背景。

检查私有目录可使用 debug/run-as：

```bash
adb shell run-as vip.mystery0.pixel.text.debug \
  find files/theme_assets -maxdepth 1 -type f
adb shell run-as vip.mystery0.pixel.text.debug \
  find cache/theme_drafts -maxdepth 2 -type f
```

保存或放弃后不应残留无引用草稿。

- [ ] **Step 8: 验证恢复、持久化和全局时间格式**

- 修改日间和暗黑配置。
- “恢复当前主题”后不保存，退出并不保存，确认原配置仍在。
- 再恢复当前主题并保存，确认只重置当前主题 appearance。
- “恢复全部”二次确认后保存，确认 SIM、placeholder、缩放和两套 appearance 全部恢复默认。
- 修改并保存后强制停止、重启应用，确认配置恢复。
- 在全局设置中切换时间格式，确认预览和真实会话读取新格式，但配置页面中没有独立时间设置。

- [ ] **Step 9: 尝试验证高对比度和 Dynamic Color**

先查询可能的高对比度设置：

```bash
adb shell settings list secure | grep -i contrast
```

如果模拟器可切换，通过系统设置 UI 操作，不直接依赖未确认的 secure key。确认开启后：

- 颜色和背景控件置灰并提示原因。
- 预览和真实会话忽略自定义颜色和图片。
- SIM、placeholder、缩放仍有效。
- 关闭后原配置自动恢复。

如果模拟器支持壁纸更换，修改壁纸后确认 MD3 角色颜色随 Dynamic Color 变化。无法覆盖时记录为残余验证项。

- [ ] **Step 10: 检查日志和崩溃**

清理日志并重走保存、图片和退出路径：

```bash
adb logcat -c
# 执行关键交互
adb logcat -d | grep -E \
  "AndroidRuntime|FATAL EXCEPTION|theme config|theme image|pixel.text"
```

Expected: 无 `FATAL EXCEPTION`、OOM、图片路径异常或主题 JSON 解析异常。

- [ ] **Step 11: 修复模拟器发现的问题并重新验证**

每个缺陷先记录复现步骤，再做最小修复。修复后至少重新执行：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

然后重走受影响的模拟器路径。

- [ ] **Step 12: 提交模拟器验证修复**

仅在有修复时提交：

```bash
git add <修复文件>
git commit -m "fix: 修复会话主题模拟器验证问题"
```

- [ ] **Step 13: 准备最终完成报告**

报告必须列出：

- Gradle 编译、Lint、assemble 命令和结果。
- 模拟器设备、Android/API、分辨率。
- 实际执行的交互路径。
- 保存于 `build/` 的截图或 UI dump 证据名称。
- `adb logcat` 检查结果。
- 未覆盖的真实双卡、特定 OEM 高对比度或真机性能风险。
- `git status --short`，确认没有误提交构建产物或临时模拟器文件。
