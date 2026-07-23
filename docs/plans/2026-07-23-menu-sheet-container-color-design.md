# 菜单 Bottom Sheet 容器色设计

## 问题

首页菜单的 `ModalBottomSheet` 默认使用 `surfaceContainerLow`，内部 `ListItem` 默认使用
`surface` 且无圆角。两种语义色在浅色主题下形成明显反差，连续列表项因此看起来像一整块方形白色背景。

## 方案

- 为 `MenuSheetContent` 中的所有 `ListItem` 复用同一份 `ListItemDefaults.colors`。
- 将列表项 `containerColor` 设置为 `MaterialTheme.colorScheme.surfaceContainerLow`，与
  Bottom Sheet 默认容器色保持一致。
- 保留当前已移除的列表项 elevation，不恢复阴影。
- 不修改列表项形状、尺寸、间距、文字、图标和点击行为。
- `MenuSheetContent` 是共享组件，因此首页和验证码页的同款菜单保持一致。

## 验证

- 执行 `./gradlew :app:compileDebugKotlin`。
- 执行 `./gradlew :app:lintDebug`。
- 检查浅色、暗色和动态取色下，列表区域不再形成额外的方形白色色块。
