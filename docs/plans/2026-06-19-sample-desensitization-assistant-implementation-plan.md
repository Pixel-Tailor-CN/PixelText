# Sample Desensitization Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为“上报脱敏样本”页面增加本地 BigBang 风格辅助脱敏流程，让用户选择真实敏感片段并替换为格式相似的虚拟假数据。

**Architecture:** 本地脱敏逻辑放在 `domain/sample`，负责分片、假数据生成和替换；`SampleSubmissionViewModel` 管理辅助脱敏工作副本和选区状态；`SampleSubmissionScreen` 只负责渲染按钮、底部面板、片段选择、类型选择和应用确认。上传接口和 Hub client 不变。

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit4, Gradle Android Plugin.

## Global Constraints

- 使用中文回复，生成的代码注释和文档使用中文，日志打印使用英文。
- UI 必须使用 Jetpack Compose，不新增 XML layout。
- 全部辅助脱敏流程必须在端侧完成，不新增网络依赖。
- 不引入中文 NLP、云端识别或外部实体抽取服务。
- 不自动勾选“我确认已脱敏”复选框。
- 不改变 PixelText Hub 的样本提交协议。
- 新增本地行为先写失败测试，再写生产代码。

---

## File Structure

- Modify: `gradle/libs.versions.toml`
  - 新增 JUnit4 版本和依赖别名。
- Modify: `app/build.gradle.kts`
  - 增加 `testImplementation(libs.junit)`。
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/SampleTextTokenizerTest.kt`
  - 验证短信分片和中文单字选择单元。
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/FakeSampleGeneratorTest.kt`
  - 验证各敏感类型的 fake 数据格式。
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizerTest.kt`
  - 验证替换范围、非法选区和连续替换。
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SensitiveType.kt`
  - 定义敏感类型和中文标签。
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleTextTokenizer.kt`
  - 提供 `tokenize()` 和 `selectionUnits()`。
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/FakeSampleGenerator.kt`
  - 按类型生成虚拟假数据。
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizer.kt`
  - 提供安全替换 API。
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizationAssistant.kt`
  - 管理辅助脱敏工作副本、选区、类型和替换动作。
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SampleSubmissionViewModel.kt`
  - 接入辅助脱敏状态和操作。
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SampleSubmissionScreen.kt`
  - 增加 `辅助脱敏` 按钮和底部面板。

## Task 1: Add Test Dependencies and Failing Domain Tests

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/SampleTextTokenizerTest.kt`
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/FakeSampleGeneratorTest.kt`
- Create: `app/src/test/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizerTest.kt`

**Interfaces:**
- Produces tests for:
  - `SampleTextTokenizer.tokenize(content: String): List<SampleTextToken>`
  - `SampleTextTokenizer.selectionUnits(content: String): List<SampleTextToken>`
  - `FakeSampleGenerator.generate(type: SensitiveType, source: String): String`
  - `SampleDesensitizer.replace(content: String, start: Int, end: Int, type: SensitiveType): String`
  - `SampleDesensitizationAssistant` state transitions

- [ ] **Step 1: Add JUnit dependency aliases**

In `gradle/libs.versions.toml` add:

```toml
junit = "4.13.2"

junit = { group = "junit", name = "junit", version.ref = "junit" }
```

- [ ] **Step 2: Add app unit test dependency**

In `app/build.gradle.kts` add inside `dependencies`:

```kotlin
testImplementation(libs.junit)
```

- [ ] **Step 3: Write tokenizer failing tests**

Create `SampleTextTokenizerTest.kt` with tests that assert:

```kotlin
val tokens = SampleTextTokenizer().tokenize("张三尾号1234\n取件码A8")
assertEquals(listOf("张三尾号", "1234", "\n", "取件码", "A8"), tokens.map { it.text })
assertEquals(0, tokens[0].start)
assertEquals(4, tokens[0].end)

val units = SampleTextTokenizer().selectionUnits("张三尾号1234")
assertEquals(listOf("张", "三", "尾", "号", "1234"), units.map { it.text })
```

- [ ] **Step 4: Write fake generator failing tests**

Create `FakeSampleGeneratorTest.kt` with tests that assert:

```kotlin
val generator = FakeSampleGenerator(Random(1))
assertTrue(generator.generate(SensitiveType.PHONE, "13812345678").matches(Regex("1[35789]\\d{9}")))
assertTrue(generator.generate(SensitiveType.ID_CARD, "110101199001011234").matches(Regex("\\d{17}[0-9X]")))
assertTrue(generator.generate(SensitiveType.BANK_CARD, "6222001234567890123").matches(Regex("\\d{19}")))
assertTrue(generator.generate(SensitiveType.ORDER_ID, "SF123ABC").matches(Regex("[A-Z]{2}\\d{3}[A-Z]{3}")))
assertTrue(generator.generate(SensitiveType.VERIFICATION_CODE, "893421").matches(Regex("\\d{6}")))
assertTrue(generator.generate(SensitiveType.AMOUNT, "123.45元").matches(Regex("\\d{1,3}\\.\\d{2}元")))
```

- [ ] **Step 5: Write desensitizer failing tests**

Create `SampleDesensitizerTest.kt` with tests that assert:

```kotlin
val desensitizer = SampleDesensitizer(FakeSampleGenerator(Random(2)))
val result = desensitizer.replace("收件人张三，电话13812345678", 3, 5, SensitiveType.NAME)
assertTrue(result.startsWith("收件人"))
assertTrue(result.endsWith("，电话13812345678"))
assertNotEquals("收件人张三，电话13812345678", result)

assertEquals("abc", desensitizer.replace("abc", -1, 2, SensitiveType.OTHER))
assertEquals("abc", desensitizer.replace("abc", 2, 2, SensitiveType.OTHER))
```

- [ ] **Step 6: Run tests and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "vip.mystery0.pixel.text.domain.sample.*"
```

Expected: FAIL because `SampleTextTokenizer`, `FakeSampleGenerator`, `SensitiveType`, and `SampleDesensitizer` do not exist.

## Task 2: Implement Local Desensitization Domain Logic

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SensitiveType.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleTextTokenizer.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/FakeSampleGenerator.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizer.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/sample/SampleDesensitizationAssistant.kt`

**Interfaces:**
- Produces:
  - `enum class SensitiveType(val label: String)`
  - `data class SampleTextToken(val text: String, val start: Int, val end: Int, val selectable: Boolean, val kind: SampleTokenKind)`
  - `enum class SampleTokenKind`
  - `class SampleTextTokenizer`
  - `class FakeSampleGenerator`
  - `class SampleDesensitizer`
  - `data class DesensitizationAssistantState`
  - `class SampleDesensitizationAssistant`

- [ ] **Step 1: Implement sensitive type enum**

Create `SensitiveType.kt`:

```kotlin
enum class SensitiveType(val label: String) {
    NAME("姓名"),
    PHONE("手机号"),
    ID_CARD("身份证号"),
    ADDRESS("地址"),
    BANK_CARD("银行卡号"),
    ORDER_ID("订单号"),
    VERIFICATION_CODE("验证码"),
    AMOUNT("金额"),
    OTHER("其他")
}
```

- [ ] **Step 2: Implement tokenizer**

Create `SampleTextTokenizer.kt` with:

```kotlin
class SampleTextTokenizer {
    fun tokenize(content: String): List<SampleTextToken>
    fun selectionUnits(content: String): List<SampleTextToken>
}
```

Rules:

- Consecutive Chinese chars are grouped in `tokenize()`.
- Consecutive digits are grouped.
- Consecutive ASCII letters and digits are grouped.
- Whitespace and punctuation are preserved as non-selectable separators.
- `selectionUnits()` splits Chinese groups into single-character selectable units.

- [ ] **Step 3: Implement fake generator**

Create `FakeSampleGenerator.kt` with:

```kotlin
class FakeSampleGenerator(
    private val random: Random = Random.Default
) {
    fun generate(type: SensitiveType, source: String): String
}
```

Rules:

- `PHONE` returns 11 digits matching `1[35789]\d{9}`.
- `ID_CARD` returns 18 chars matching `\d{17}[0-9X]`.
- `BANK_CARD` returns 16 or 19 digits based on the source digit count.
- `ORDER_ID` preserves each letter/digit position type.
- `VERIFICATION_CODE` preserves numeric or alphanumeric shape.
- `AMOUNT` preserves decimal places and `元` suffix when present.
- `OTHER` maps Chinese to `某`, digits to random digits, letters to random letters, and leaves punctuation unchanged.

- [ ] **Step 4: Implement desensitizer**

Create `SampleDesensitizer.kt`:

```kotlin
class SampleDesensitizer(
    private val generator: FakeSampleGenerator = FakeSampleGenerator()
) {
    fun replace(content: String, start: Int, end: Int, type: SensitiveType): String
}
```

Rules:

- Return original content when `start < 0`, `end > content.length`, or `start >= end`.
- Replace only `content.substring(start, end)`.
- Keep non-selected content unchanged.

- [ ] **Step 5: Implement assistant state machine**

Create `SampleDesensitizationAssistant.kt` with state fields:

```kotlin
data class DesensitizationAssistantState(
    val visible: Boolean = false,
    val draft: String = "",
    val tokens: List<SampleTextToken> = emptyList(),
    val selectedStart: Int? = null,
    val selectedEnd: Int? = null,
    val selectedType: SensitiveType? = null,
    val dirty: Boolean = false
)
```

Methods:

```kotlin
fun open(content: String): DesensitizationAssistantState
fun close(): DesensitizationAssistantState
fun selectRange(state: DesensitizationAssistantState, start: Int, end: Int): DesensitizationAssistantState
fun updateType(state: DesensitizationAssistantState, type: SensitiveType): DesensitizationAssistantState
fun replaceSelected(state: DesensitizationAssistantState): DesensitizationAssistantState
```

- [ ] **Step 6: Run domain tests and verify GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "vip.mystery0.pixel.text.domain.sample.*"
```

Expected: PASS.

## Task 3: Connect Assistant State to ViewModel

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SampleSubmissionViewModel.kt`

**Interfaces:**
- Consumes: `SampleDesensitizationAssistant`, `DesensitizationAssistantState`, `SensitiveType`.
- Produces methods used by UI:
  - `openDesensitizationAssistant()`
  - `requestCloseDesensitizationAssistant(): Boolean`
  - `discardDesensitizedDraft()`
  - `selectSensitiveRange(start: Int, end: Int)`
  - `updateSensitiveType(type: SensitiveType)`
  - `replaceSelectedSensitiveText()`
  - `applyDesensitizedDraft()`

- [ ] **Step 1: Add assistant dependency and state**

Update constructor:

```kotlin
class SampleSubmissionViewModel(
    private val repository: SampleSubmissionRepository,
    private val desensitizationAssistant: SampleDesensitizationAssistant = SampleDesensitizationAssistant(),
) : ViewModel()
```

Add state:

```kotlin
var desensitizationState by mutableStateOf(DesensitizationAssistantState())
    private set
```

- [ ] **Step 2: Add assistant operations**

Add:

```kotlin
fun openDesensitizationAssistant() {
    if (content.isBlank()) return
    desensitizationState = desensitizationAssistant.open(content)
}

fun requestCloseDesensitizationAssistant(): Boolean {
    if (desensitizationState.dirty) return false
    discardDesensitizedDraft()
    return true
}

fun discardDesensitizedDraft() {
    desensitizationState = desensitizationAssistant.close()
}

fun selectSensitiveRange(start: Int, end: Int) {
    desensitizationState = desensitizationAssistant.selectRange(desensitizationState, start, end)
}

fun updateSensitiveType(type: SensitiveType) {
    desensitizationState = desensitizationAssistant.updateType(desensitizationState, type)
}

fun replaceSelectedSensitiveText() {
    desensitizationState = desensitizationAssistant.replaceSelected(desensitizationState)
}

fun applyDesensitizedDraft() {
    val draft = desensitizationState.draft
    if (draft.isBlank()) return
    content = draft
    agreed = false
    discardDesensitizedDraft()
}
```

- [ ] **Step 3: Run domain tests and compile**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "vip.mystery0.pixel.text.domain.sample.*"
./gradlew :app:compileDebugKotlin
```

Expected: PASS and BUILD SUCCESSFUL.

## Task 4: Add Compose Assistant UI

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SampleSubmissionScreen.kt`

**Interfaces:**
- Consumes ViewModel methods from Task 3.
- Produces user-facing UI:
  - `辅助脱敏` button.
  - `ModalBottomSheet` BigBang token selector.
  - Sensitive type chips.
  - Preview text.
  - `替换` and `应用到样本` actions.
  - Discard confirmation dialog.

- [ ] **Step 1: Add assistant button near sample input**

Wrap the sample text field in a `Column` and add:

```kotlin
OutlinedButton(
    onClick = viewModel::openDesensitizationAssistant,
    enabled = viewModel.content.isNotBlank(),
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Rounded.Edit, contentDescription = null)
    Text("辅助脱敏", modifier = Modifier.padding(start = 8.dp))
}
```

- [ ] **Step 2: Add bottom sheet composable**

Create private composable:

```kotlin
@Composable
private fun DesensitizationAssistantSheet(
    state: DesensitizationAssistantState,
    onSelectRange: (Int, Int) -> Unit,
    onTypeSelected: (SensitiveType) -> Unit,
    onReplace: () -> Unit,
    onApply: () -> Unit,
    onDismissRequest: () -> Unit,
)
```

It renders:

- `FlowRow` of `state.tokens`.
- `FilterChip` for selectable tokens.
- `AssistChip` or `FilterChip` for `SensitiveType.entries`.
- Read-only `OutlinedTextField` preview of `state.draft`.
- `替换` enabled only when `state.canReplace` is true.
- `应用到样本` enabled only when `state.draft.isNotBlank()`.

- [ ] **Step 3: Add discard confirmation**

When the sheet requests dismissal:

```kotlin
if (!viewModel.requestCloseDesensitizationAssistant()) {
    showDiscardDialog = true
}
```

Dialog buttons:

- `继续编辑` dismisses the dialog.
- `放弃修改` calls `viewModel.discardDesensitizedDraft()`.

- [ ] **Step 4: Run compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

## Task 5: Final Verification

**Files:**
- Verify all files changed by Tasks 1-4.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "vip.mystery0.pixel.text.domain.sample.*"
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect git diff**

Run:

```bash
git diff --check
git status --short
```

Expected:

- `git diff --check` prints no errors.
- `git status --short` only lists intended files until commit.

- [ ] **Step 4: Manual UI smoke test on device or emulator**

Manual steps:

- Open Settings.
- Open `上报脱敏短信样本`.
- Enter a sample containing a name, phone, address, order id, and amount.
- Tap `辅助脱敏`.
- Select a sensitive range.
- Select a type.
- Tap `替换`.
- Confirm preview changes.
- Tap `应用到样本`.
- Confirm input field updates and privacy checkbox remains unchecked.

If no Android runtime is available in this session, record that manual UI smoke test was not run and rely on compile plus unit tests for local verification.
