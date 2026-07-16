# 应用启动磁贴实施计划

> **执行要求：** 按任务顺序实现并逐项验证，不新增单元测试或测试依赖。

**目标：** 增加一个无状态的 Android 快捷设置磁贴，点击后收起快捷设置面板并启动 Pixel Text 主界面。

**架构：** 使用独立的 `TileService` 作为系统入口，通过版本分支调用 `startActivityAndCollapse`。服务不依赖业务层和依赖注入，只在 Manifest 中注册系统要求的权限、动作和元数据。

**技术栈：** Kotlin、Android `TileService`、`PendingIntent`、Android Manifest。

## 全局约束

- 最低 SDK 为 31。
- UI 与业务逻辑均不增加状态配置。
- 不新增单元测试、仪器测试或测试依赖。
- 代码注释使用中文，日志如有需要使用英文；本组件无需日志。

---

### 任务一：实现应用启动磁贴服务

**文件：**

- 新建：`app/src/main/java/vip/mystery0/pixel/text/service/AppLauncherTileService.kt`

**接口：**

- 继承 `android.service.quicksettings.TileService`。
- `onStartListening()` 将磁贴固定设为 `Tile.STATE_ACTIVE` 并刷新，避免呈现为已关闭的开关。
- `onClick()` 创建指向 `MainActivity` 的启动 Intent。
- Android 14 及以上使用 `PendingIntent` 重载；Android 12 至 13 使用 `Intent` 重载。

**步骤：**

- [ ] 新建服务类，实现无状态磁贴刷新。
- [ ] 实现带 `FLAG_ACTIVITY_NEW_TASK`、`FLAG_ACTIVITY_CLEAR_TOP` 的主界面 Intent。
- [ ] 实现 API 34 的 `PendingIntent` 分支和低版本兼容分支。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，预期编译成功。

### 任务二：注册系统磁贴组件

**文件：**

- 修改：`app/src/main/AndroidManifest.xml`

**接口：**

- 服务权限：`android.permission.BIND_QUICK_SETTINGS_TILE`。
- Intent action：`android.service.quicksettings.action.QS_TILE`。
- 元数据：`android.service.quicksettings.ACTIVE_TILE=false`、`android.service.quicksettings.TOGGLEABLE_TILE=false`。
- 标签：`@string/app_name`。
- 图标：`@drawable/ic_notification_sms`。

**步骤：**

- [ ] 在 application 下注册仅允许持有系统磁贴绑定权限调用的导出服务。
- [ ] 声明磁贴 action 和无状态元数据。
- [ ] 运行 `./gradlew :app:lintDebug`，预期检查成功。

### 任务三：安装并进行系统级验证

**文件：** 无。

**步骤：**

- [ ] 运行 `./gradlew :app:installDebug` 安装到已启动的模拟器。
- [ ] 使用系统命令确认 TileService 已被 PackageManager 正确解析。
- [ ] 将 Pixel Text 磁贴加入快捷设置面板。
- [ ] 点击磁贴，确认面板收起并启动 `MainActivity`。
- [ ] 在应用已经运行的情况下再次点击，确认复用现有任务。
- [ ] 运行 `git diff --check` 并核对工作区只包含本功能改动。
