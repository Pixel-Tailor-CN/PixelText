# 菜单 Bottom Sheet 容器色实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让菜单列表项与 Bottom Sheet 使用一致的 Material 3 Expressive 容器色。

**Architecture:** 在共享的 `MenuSheetContent` 中创建一份列表项颜色配置，并传给全部菜单项。只调整 `containerColor`，其他状态、形状和交互继续由 Material 3 组件管理。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3 Expressive

## Global Constraints

- UI 使用 Jetpack Compose，不新增 XML layout。
- 使用 `MaterialTheme.colorScheme.surfaceContainerLow`，保留 Dynamic Color 行为。
- 不新增或运行单元测试。
- 避免无关格式化和重构。
- 保留 `ConversationListScreen.kt` 中已有的未提交修改。

---

### Task 1: 统一菜单列表项容器色

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt`

**Interfaces:**
- Consumes: `MenuSheetContent` 现有参数和共享调用方式。
- Produces: 视觉上与 Bottom Sheet 容器融合的现有 `MenuSheetContent`。

- [x] **Step 1: 创建共享颜色配置**

在 `MenuSheetContent` 顶部创建：

```kotlin
val menuItemColors = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
)
```

- [x] **Step 2: 应用到全部菜单项**

为“设置默认短信应用”“归档短信”“标记所有会话为已读”“骚扰与拦截”“设置”和可选的
“开发测试（MOCK）”列表项传入：

```kotlin
colors = menuItemColors
```

- [x] **Step 3: 编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：构建成功，无 Kotlin 编译错误。

- [x] **Step 4: Lint 验证**

运行：

```bash
./gradlew :app:lintDebug
```

预期：构建成功，未引入新的阻断问题。

- [x] **Step 5: 检查改动范围**

运行：

```bash
git diff --check
git diff -- app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt
```

预期：保留已有 elevation 删除，仅新增共享语义色配置并应用到全部菜单项。
