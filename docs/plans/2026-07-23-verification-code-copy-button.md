# 验证码复制按钮窄屏适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 缩小验证码聚合页的复制按钮，确保窄屏设备完整显示 6 位验证码。

**Architecture:** 在通用验证码卡片上增加默认关闭的紧凑操作参数，由聚合页显式启用。紧凑模式只改变按钮尺寸、图标和相邻间距，不影响复制逻辑及其他调用方。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3

## Global Constraints

- UI 使用 Jetpack Compose，不新增 XML layout。
- 不新增或运行单元测试。
- 代码注释和文档使用中文，日志使用英文。
- 避免无关格式化和重构。

---

### Task 1: 增加并启用紧凑复制按钮

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/message/cards/VerificationCodeCard.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/VerificationCodeScreen.kt`

**Interfaces:**
- Consumes: `VerificationCodeCard(content, result, isSelected)` 现有调用方式。
- Produces: `VerificationCodeCard(content, result, isSelected, compactCopyButton)`，新增参数默认值为 `false`。

- [x] **Step 1: 修改验证码卡片**

为 `VerificationCodeCard` 增加 `compactCopyButton: Boolean = false`。紧凑模式使用
48dp 的复制图标按钮和 12dp 间距；默认模式保留 56dp 高、至少 104dp 宽的文字按钮和
20dp 间距。

- [x] **Step 2: 在聚合页启用紧凑模式**

在 `VerificationIndexCard` 调用 `VerificationCodeCard` 时传入
`compactCopyButton = true`，其他调用方不传该参数。

- [x] **Step 3: 编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：任务成功，无 Kotlin 编译错误。

- [x] **Step 4: Lint 验证**

运行：

```bash
./gradlew :app:lintDebug
```

预期：任务成功，未引入新的阻断问题。

- [x] **Step 5: 检查改动范围**

运行：

```bash
git diff --check
git diff -- app/src/main/java/vip/mystery0/pixel/text/ui/message/cards/VerificationCodeCard.kt app/src/main/java/vip/mystery0/pixel/text/ui/screen/VerificationCodeScreen.kt
```

预期：仅包含紧凑按钮参数、渲染逻辑和聚合页启用代码。
