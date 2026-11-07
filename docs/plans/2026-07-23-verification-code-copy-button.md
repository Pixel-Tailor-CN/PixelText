# 验证码复制按钮适配实现与历史验证

## 当前组件

`ui/message/cards/VerificationCodeCard.kt` 当前接口为 `VerificationCodeCard(content, result, isSelected, showOriginal, originalLoading)`。复制使用 40dp 的 `FilledIconButton`、20dp 复制图标与 12dp 间距；Material 组件处理最小交互区域。

早期 `compactCopyButton: Boolean = false` 和“仅聚合页启用”的方案已经被统一图标按钮替代。`ui/screen/VerificationCodeScreen.kt` 不再传该参数；会话详情复用相同卡片。当前布局与行为见[设计](2026-07-23-verification-code-copy-button-design.md)。

## 原实施记录（2026-07-23）

当时增加紧凑参数及聚合页启用的两项实现已完成；下列编译、Lint 和差异检查记录保留为当时版本的证据，其中“仅包含紧凑按钮参数”的范围描述不是当前实现要求。本次文档核对没有重跑 UI 或构建。

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
