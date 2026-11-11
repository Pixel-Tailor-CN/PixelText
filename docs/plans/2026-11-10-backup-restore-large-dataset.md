# 大数据备份恢复与恢复页优化实施计划

> 本计划按当前用户授权直接执行；验证使用模拟器和脱敏的数量/耗时断言，不记录短信正文或号码。

**目标：** 让 7000+ 条短信恢复期间不再逐条触发镜像同步和全量扫描，并改善“备份与恢复”页面的信息层级与操作反馈。

**架构：** 恢复开始时暂停短信变更观察器和普通镜像 Worker 的消费，Provider 写入只由恢复流程负责；恢复完成或中断时恢复观察器，并统一执行一次镜像同步、分类和验证码索引重建。恢复页改为按“导出备份 / 从文件恢复 / 恢复保护”分组，突出当前阶段、数量和唯一主操作。

**技术栈：** Kotlin、Coroutines、WorkManager、Room、Jetpack Compose、Material 3、ADB 集成验证。

**验证重点：** 7742 条短信、重复导入、恢复中断、系统 Provider 变更回调、恢复期间后台 Worker、恢复保护未结束、空数据和无密码备份。

---

### 任务 1：停止旧恢复任务并建立大数据基线

**文件：** `docs/.local/restore-7k/` 下的验证脚本和结果。

- [x] 停止遗留恢复进程，记录 Provider 当前数量和应用进程状态。
- [x] 增加仅记录数量、阶段、耗时、GC/Worker 计数的验证脚本。
- [x] 记录修复前基线：恢复速率、全量扫描次数、增量批次次数、最终 Provider 和镜像数量。

**验收：** 验证过程不输出正文/号码；基线可重复运行。

### 任务 2：恢复期间隔离短信变更同步

**文件：**
- 修改 `app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MirrorChangeObserver.kt`
- 修改 `app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MessageMirrorIncrementalSynchronizer.kt`
- 修改 `app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MessageMirrorSynchronizer.kt`
- 修改 `app/src/main/java/vip/mystery0/pixel/text/data/repository/BackupRepositoryImpl.kt`
- 必要时修改 `app/src/main/java/vip/mystery0/pixel/text/worker/MessageMirrorWorker.kt`

**接口约定：** 增加进程内可复用的恢复同步门闩/协调器；恢复期间观察器不提交普通 wake/dirty，Worker 发现恢复门闩时延后而不是扫描；释放门闩时保留一次集合级 dirty 或直接由恢复流程执行一次完整同步。

- [x] 先用 `docs/.local` 的集成断言复现“每条恢复写入触发同步/全量扫描”的失败基线。
- [x] 实现恢复门闩，保证恢复开始、正常完成、取消、异常四条路径都释放门闩。
- [x] 让观察器在门闩期间只记录待处理事实，不逐条调度同步。
- [x] 让恢复完成后的单次同步覆盖新增、删除、镜像生成、分类和验证码索引。
- [x] 处理恢复中途进程被杀：保留恢复保护和可继续/可确认状态，不留下永久暂停的观察器。
- [x] 用 `ContentResolver.applyBatch` 分批写入 Provider，并按批持久化恢复保护身份；每批仍使用返回 URI 关联目标 ID。

**验收：** 7742 条导入期间无逐条全量对账；恢复完成后镜像与 Provider 数量一致；恢复中断后重启仍可继续或明确提示，不会永久丢失同步。

### 任务 3：恢复页交互整理

**文件：**
- 修改 `app/src/main/java/vip/mystery0/pixel/text/ui/screen/BackupRestoreScreen.kt`
- 必要时修改 `app/src/main/java/vip/mystery0/pixel/text/domain/backup/BackupRepository.kt`

- [x] 统一页首状态反馈，明确区分“预览文件”“正在恢复”“恢复已完成但保护未结束”“失败可重试”。
- [x] 将导出选项、恢复入口和恢复结果分为清晰卡片，减少重复密码输入和重复操作入口。
- [x] 恢复预览显示条目数量、备份时间、内容范围和不可恢复项；主按钮只保留当前阶段最重要动作。
- [x] 大数量恢复显示已处理/总数和预计耗时信息，取消按钮明确“停止后续处理，已写入数据保留”。
- [x] 保留暗色模式、动态颜色、无障碍文本和现有权限引导。
- [x] 将最终镜像对账纳入恢复完成状态，完成前保持“正在对账和重建索引”。

**验收：** 空数据、无密码备份、恢复中、失败、恢复保护五种状态均能从当前页面理解下一步；不需要依赖滚动到底部才能发现错误或主操作。

### 任务 4：编译、模拟器和回归验证

- [x] 运行 `./gradlew.bat :app:compileDebugKotlin :app:assembleDebug :app:lintDebug`。
- [x] 在干净模拟器状态导入 7742 条备份，记录恢复总耗时、同步批次、最终数量和首屏列表发布时间。
- [x] 验证重复导入不重复插入，恢复保护确认，搜索、验证码、垃圾识别和会话列表路由。
- [x] 检查 `git diff --check`、工作区差异和临时文件未进入 Git。

**完成标准：** 7742 条数据恢复成功；同步扫描不随每条 Provider 写入线性放大；批量写入约 30 条/秒，相比单条写入约 10～14 条/秒；最终镜像对账完成后才显示操作完成；页面各状态有明确反馈；编译、Lint 和模拟器关键链路通过。
