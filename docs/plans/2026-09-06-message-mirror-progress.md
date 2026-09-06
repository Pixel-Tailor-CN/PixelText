# 消息镜像执行记录

计划：[完整消息镜像实施计划](2026-09-06-message-mirror.md)。基线：`2f5e590`；实施分支：`codex/message-mirror`。

## 实现结果

- 新建独立 Room 镜像库，保存 SMS/MMS 全部箱类型、可逆原始列、完整 addr/part、系统会话和 canonical 地址快照。源键为 transport + sourceId，现有正/负 ID 仅为兼容投影。
- 200 条键集分页、持久整轮状态、dirty 令牌确认、成功空结果复核后删除；异常、权限失败和 null Cursor 不按空源处理。
- 已下载附件流式复制到 noBackupFilesDir，记录大小与 SHA-256，原子改名并复核 revision；失败退避、缺失恢复和删除回收持久化。未下载 MMS 只保留待下载状态。
- 元数据和附件任务分开，合并高频唤醒，前台恢复和每日任务补对账。系统写成功后先持久排队，Receiver 不等待整库扫描。
- 会话列表、详情和现有搜索读取本地消息。详情保留分页，搜索随镜像更新；无 thread 消息支持独立只读详情。新检索筛选栏属于后续需求，本次没有提前实现。
- 自动下载彩信默认关闭；设置允许用户开启，仅影响后续新通知。手动下载使用接收 SIM，写入原通知记录，失败保留有效 PDU 本地重试；回调令牌去重、地址清理日志和源存在性检查支持恢复。
- 保留用户既有的 `gradle/libs.versions.toml` 与 `gradle/wrapper/gradle-wrapper.properties` 修改，不暂存这两个文件。

## 已完成验证

| 检查 | 结果 |
| --- | --- |
| Debug APK 构建、Kotlin/Java 编译 | `:app:assembleDebug` 成功 |
| Android Lint | `:app:lintDebug` 成功，无 error；仍有项目提示/警告 |
| 安装运行 | `emulator-5554` 安装升级成功，已有业务库保留 |
| 首次导入 | 系统 104 条 SMS；镜像 104 条（收件箱 99、已发 5）；SMS/MMS/THREADS/CANONICAL 及 ROUND 状态完成 |
| 新增 | 通过模拟器短信入口注入脱敏标记，源 ID 107 进入镜像；未向真实号码发短信 |
| 已读 | 从应用打开该样本，镜像 read 由 0 更新为 1 |
| 删除 | 仅删除本次样本，系统查询为空，镜像该源键计数为 0 |
| 彩信结构 | 创建本次独立样本后，待下载 NotificationInd 与 null thread 保留；改为已下载结构后，文本、SMIL、未知二进制、音频和视频 MIME 共 5 个 part 全部入库 |
| 附件字节 | 3 个二进制 part 均复制为 READY，21 字节，SHA-256 与已知输入一致；文本和 SMIL 的 Provider 内联内容保留于本地快照 |
| 同大小内容更新 | part 元数据不变，21 字节 v1 改为 v2；手动下拉刷新后私有副本 SHA-256 更新为预期值 |
| 彩信删除与回收 | 删除本次彩信后，镜像 MMS 与附件行数均为 0，私有附件目录为空；104 条原始 SMS 保留 |
| 无会话详情 | 在搜索页点击无 thread 的彩信，独立详情显示正文、SMIL 和 3 个附件元数据 |
| 设置 | 设置页“自动下载彩信”存在且 checked=false，未开启该项 |
| 审查 | 核心、下载及最终接入审查所列问题全部修正；最终限定范围内无未解决 P0/P1/P2 |

未新增测试依赖、单元测试或 instrumentation 测试，也未运行 test 任务。Lint 自身生成测试源集分析模型不代表执行测试。

## 验收边界与待真机验证

模拟器初始没有 MMS。本次通过 shell 创建独立的脱敏彩信结构；shell 查询未返回这些记录，但在应用完整对账后确认源写入生效。临时应用进程准备方式被系统拒绝，已移除辅助 dex。附件样本用于验证 MIME 无关的完整字节复制，不代表音频/视频可播放性或运营商下载已经通过。

仍需使用真实设备和脱敏样本核验：

- 草稿、发件箱、失败、排队箱及无 thread 消息的全字段比对。
- 多地址、多文本、SMIL、图片、音频、视频和未知 MIME 的 part 数量、字节数与摘要；同大小附件变更与删除后的私有文件回收。
- 真实运营商下载、双卡/SIM 失效、默认关闭与开启后的接收行为、失败回调和进程死亡窗口。
- 磁盘满、复制中断、权限撤销、null Cursor/查询失败、ID 复用和大量历史数据的故障恢复。

上述运行时验证仅使用合成内容；本次样本已清理，shell WRITE_SMS AppOp 已恢复 ignore。

这些是运行时验收缺口，不能以编译或静态审查替代。代码已具备相应状态/恢复路径，但本记录不声称完成真机数据完整性证明。

## 与计划的具体落地差异

- 下载请求日志沿用独立 SharedPreferences 持久化，镜像数据库预留请求表不作为当前下载日志来源；文件及请求配置排除备份。
- 原始下载 PDU 完成后保留在私有目录，作为未知头字段的保真副本；结束时撤销临时 URI 授权，系统消息删除后同步清理 PDU。不会为保存失败重新自动联网。
- 无明确源键的系统写通知通过持久集合 dirty 合并对账；可解析消息 URI 由 Observer 定向标记，避免把 URI 尾部 ID 当作 thread ID。
- 兼容模型映射放在 MessageMirrorRepositoryImpl 中；独立详情使用按源键持续观察接口，避免全库解码。
- 已有验证码索引/短信正文补查仍通过 VerificationCodeRepositoryImpl 的 TelephonyDataSource；HistoricalSpamScanWorker、KeywordSpamRebuildWorker 仍读取 Provider。它们是独立派生任务，本次没有改造其索引策略。

## 评审资料

- [核心实施记录](2026-09-06-message-mirror-core-report.md)
- [核心初审](2026-09-06-message-mirror-core-review.md)
- [下载链路复审](2026-09-06-message-mirror-download-review.md)
- [最终接入审查](2026-09-06-message-mirror-final-review.md)


## 附件原地变更修正

可解析 part 变更事件使用独立持久 dirty 标记。成功回读后，事务提升消息与所有附件 revision，将 READY/COPYING 重排，阻止旧复制任务覆盖新内容；失败任务保留退避。显式手动刷新以 200 条批次请求附件全文校验，普通前台恢复不会反复重读所有视频；缺少变更通知的内容仍由每日校验兜底。
