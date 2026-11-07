# 资源自动检查更新实现与维护计划

## 当前行为

自动资源检查已经实现，默认关闭，默认间隔为 24 小时。后台只请求资源清单并提示更新，安装仍由用户在设置页明确确认；不上传短信、联系人、解析结果或分类结果。

当前入口位于设置的资源更新区域。`AppSettingsRepository` 通过 SharedPreferences 保存：

- `resourceAutoCheckEnabled`：默认 `false`。
- `resourceAutoCheckIntervalHours`：默认 `24L`，写入及读取至少为 1 小时。
- `resourceAutoCheckLastCheckedAt`：默认 `0L`。

设置页只接受大于 0 的整数。开启和在开启状态修改间隔时，`SettingsViewModel` 更新检查基准时间并同步调度。周期任务受 WorkManager 与系统后台限制，不承诺精确到点执行。

## 调度与通知

`worker/ResourceUpdateScheduler.kt` 提供 `syncAfterSettingsChange()`、`syncOnAppStart()` 和 `enqueueImmediateCheck()`：

- 开启时安排唯一周期任务 `resource_update_auto_check`，使用 `ExistingPeriodicWorkPolicy.UPDATE`、相同长度的初始延迟和 `NetworkType.CONNECTED`。
- 修改配置时取消已有立即检查任务，再更新周期任务；关闭时取消周期、立即任务和更新提醒。
- 启动时确保周期任务存在；上次成功检查时间缺失或超期时，以 `ExistingWorkPolicy.KEEP` 排队 `resource_update_immediate_check`。

`ResourceUpdateCheckWorker` 重新读取开关，只调用 `HubResourceRepository.checkResourceUpdateAvailability()`。成功后保存检查时间，有更新时提示，无更新时取消通知；异常记录英文元数据并返回重试。它不调用 `updateAll()`。

`notification/ResourceUpdateNotificationHelper.kt` 使用 `channel_resource_updates`、`IMPORTANCE_LOW`、固定通知 ID 20020、`setShowBadge(false)`、`setOnlyAlertOnce(true)`。Android 13+ 无通知权限时不发通知。点击通知携带 `EXTRA_OPEN_SETTINGS` 和 `EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK`，只打开设置并触发检查，不自动安装。

## 当前接口与早期模板的差异

原计划的长代码模板已被现有实现替代，维护时需遵守以下事实：

| 早期模板 | 当前实现 |
| --- | --- |
| 只比较清单版本，缺项按 `builtin` 处理 | `HubResourceRepository` 先检查模型兼容性；缺项保持当前版本，两类均缺失时返回没有兼容资源 |
| 模型版本只取 SharedPreferences | 当前模型版本取活动 `HubModelBundle`，无活动组合时使用内置版本回退标记 |
| 旧资源清单接口 | `PixelTextHubClient` 请求 `api/v2/resources/manifest`，携带应用版本和模型契约版本；发送方资料保留独立接口 |
| 应用启动接线 | `PixelTextApp.kt` 负责通知渠道和调度初始化 |
| 导航消费 deep link 后再直接读取原参数 | `AppNavigation` 先把请求 ID 保存到独立状态再消费 deep link，`SettingsScreen` 完成检查请求消费后清除 |

`domain/hub/HubModels.kt` 中的 `ResourceUpdateDetail` 与 `ResourceUpdateAvailability` 仍供设置页和 Worker 共用。手动下载、模型组合校验和个人参数激活见[Hub 集成](2026-06-12-pixel-text-hub-app-integration.md)。当前代码文件均位于 `app/src/main/java/vip/mystery0/pixel/text/`。

## 验证边界

原计划只有待执行清单，没有本功能设备验收完成记录。本次仅核对源码，保留以下待运行验证：

- [ ] 按变更运行 `./gradlew.bat :app:compileDebugKotlin` 与必要的 `:app:lintDebug`，不运行单元测试。
- [ ] 默认关闭、非法间隔拒绝、修改间隔、重启超期检查和关闭取消。
- [ ] WorkManager 实际调度、断网恢复、无通知权限、重复通知覆盖。
- [ ] 无更新、清单缺项和不兼容模型的提示及通知取消行为。
- [ ] 通知冷启动和应用存活时点击均只触发检查，用户确认前没有下载或激活。

设备和网络行为不能由本次静态核对推断为已通过。
