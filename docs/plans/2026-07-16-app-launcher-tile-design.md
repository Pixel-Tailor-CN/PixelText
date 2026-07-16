# 应用启动磁贴设计

## 目标

为 Pixel Text 增加一个 Android 快捷设置磁贴。磁贴仅作为应用启动入口，不表示或切换任何业务状态；用户点击后打开 Pixel Text 主界面，并收起快捷设置面板。

## 方案

使用 Android 标准 `TileService` 实现 `AppLauncherTileService`：

- 磁贴处于可点击的无状态展示模式，不根据应用数据改变名称、图标或状态。
- 点击磁贴时创建指向 `MainActivity` 的 Intent，使用系统提供的 `startActivityAndCollapse` 启动应用。
- Intent 使用 `FLAG_ACTIVITY_NEW_TASK` 与 `FLAG_ACTIVITY_CLEAR_TOP`，复用已有应用任务并回到主界面。
- 在 `AndroidManifest.xml` 中以 `android.permission.BIND_QUICK_SETTINGS_TILE` 保护服务，并声明 `android.service.quicksettings.action.QS_TILE`。
- 磁贴标签复用 `@string/app_name`，图标复用适合系统单色着色的短信通知图标。

## 组件边界

新增组件只负责响应系统磁贴点击事件与启动应用，不读取短信、设置、数据库或应用状态，也不引入依赖注入。磁贴无需在应用设置页面提供额外配置。

## 平台兼容

项目最低版本为 Android 12（API 31）。API 34 及以上使用接收 `PendingIntent` 的 `startActivityAndCollapse`；API 31 至 33 使用接收 `Intent` 的兼容重载，避免高版本废弃 API 影响。

## 验证

- 编译 Debug 版本并运行 Lint。
- 安装到模拟器后确认系统能够识别 Pixel Text 磁贴。
- 将磁贴添加到快捷设置，点击后确认快捷设置面板收起且 Pixel Text 主界面启动。
- 重复点击并从应用已在后台的场景点击，确认不会创建异常的重复任务。
