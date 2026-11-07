# Smartspacer 未读数量实现与维护计划

## 当前结构

未读统计已实现。早期放进 `AppSettings`、`SettingsViewModel` 和通用设置页弹窗的方案已被每个 Smartspacer 实例独立配置替代，不应恢复旧接口。

| 文件（相对应用包目录） | 当前职责 |
| --- | --- |
| `smartspacer/UnreadSmsComplicationSettingsRepository.kt` | `UnreadSmsComplicationSettings` 三项默认 true；按 smartspacerId 存储，并提供默认配置与旧 app_settings 迁移读取 |
| `smartspacer/UnreadSmsComplicationConfigActivity.kt` | 从 Smartspacer 配置入口打开三个开关的 Compose 页面；保存后通知指定实例刷新，返回不保存 |
| `smartspacer/SmartspacerSmsRepository.kt` | `getUnreadSmsCount(smartspacerId: String)` 将该实例设置传给共享计数器 |
| `data/repository/UnreadSmsCounter.kt` | 查询系统未读 SMS，结合主库分类与归档执行并集统计；全部关闭返回 0 |
| `data/db/mirror/UnreadSmsQuery.kt` | 对候选 SMS 按主库最终骚扰标记与归档状态执行参数化计数 |
| `smartspacer/UnreadSmsComplicationProvider.kt` | 使用实例 ID 获取计数并提供配置入口；实例删除时清理配置 |

没有 `AppSettings.smartspacerUnreadCountSettings` 或无参的 `getUnreadSmsCount()`。通用设置页仅保留手动刷新 Smartspacer 数据等操作。

## 维护约束

普通、骚扰、归档是消息级并集，同一 SMS 最多计一次；不统计 MMS。骚扰以统一最终判定为准，包含白名单／持久放行、关键词、人工纠正和模型结果，而非仅原始分数阈值。当前主库与迁移见[主库合并方案](2026-11-05-main-database-consolidation.md)。

配置保存及短信读状态、分类和归档变化需要刷新 Smartspacer。不得改变点击跳转或验证码目标卡片的行为。

## 验证边界

原实施清单未记录本功能编译或设备验收通过，本次源码审计不补写结果。

- [ ] 按变更运行 `./gradlew.bat :app:compileDebugKotlin` 和必要 Lint。
- [ ] 在 Smartspacer 中验证各实例独立配置、保存、取消和全部关闭。
- [ ] 验证默认配置及旧设置回退，分类／归档交集不重复计数。
- [ ] 验证新短信、已读、人工纠正及归档操作后的实际刷新。

不新增或运行单元测试。
