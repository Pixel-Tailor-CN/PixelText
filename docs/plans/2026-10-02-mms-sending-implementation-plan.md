# 单人 MMS 发送实施计划

> 实施方式：使用 Superpowers writing-plans 的接口与验收拆解，以及 executing-plans / subagent-driven-development 的分工、独立评审和完成前验证。项目规范优先：正式文件放 `docs/plans/`，不新增单元测试，不机械重复已获授权的设计审批；独立模块可以并行实现，共享 Gradle 构建串行执行。

**目标：** 为一个明确电话号码实现可恢复草稿、私有附件、平台 MMS 发送与可信结果展示。

**架构：** 独立 Room 命令日志和不可变载荷管理执行态，系统 Telephony Provider 与现有镜像继续管理消息。发送前持久提交栅栏阻止进程重启或 Worker 重跑造成重复网络发送；结果未知只接受迟到结果或用户明确另发。

**技术栈：** Kotlin、Compose/Material 3、Room、WorkManager、Koin、SmsManager、既有 AOSP PDU 源码。

**设计：** [MMS 发送设计](2026-10-02-mms-sending-design.md)，已于 2026-10-02 获用户批准继续实现。

## 全局约束

- 基线 `09ce5f4d03ae1785a496b8ee833f92c00dea0f05`；保留接收多地址、会话分组、SMS、备份和 PR17 R8 修复
- 只接受一个明确电话号码，PDU 恰好一个 TO，无 CC/BCC；不拆发、换卡、删附件或改 SMS
- minSdk31 / targetSdk37 / ARM64 / JDK21；不使用隐藏 API、自建 MMSC 网络栈或新增宽泛存储权限
- 每草稿最多10附件、单原件32MiB、总原件64MiB、图片64百万像素、PDU最多10MiB且受运营商更小限额约束
- 仅静态图本地适配；动画与音视频/文件发送合规原件；配置缺失阻断
- Provider 在途用 DRAFTS，成功 SENT，其余终态 FAILED；绝不建立系统 OUTBOX/pending 自动发送任务
- 回调事实、SendConf、Provider同步和镜像状态分开；UNKNOWN 不自动重发；PDU 不放cache
- 不新增 `src/test`、`src/androidTest`、测试依赖；验证用构建、lint、现有/仓库外 mock runtime harness
- 已获单独批准推送功能分支、创建草稿 PR 与运行/修复其 CI；仍不真实发送、不合并或发布
- 在完成运营商验收前默认禁用生产发送入口；本地构建开关允许受控验证，但回调恢复始终启用

## 重点评审场景

1. 提交与取消/回调并发、回调早于方法返回：状态 CAS 和单次 dispatch 计数
2. Provider 插入/part写入后进程死亡、null Cursor和ID复用：保守停止、按事务恢复、不盲插或覆盖
3. 选卡变化、配置变化、附件准备迟到：固定快照，不静默采用新卡/新质量
4. 外部 Intent 多号码、CC/BCC、异常URI与超限流：全部阻断或编辑确认，不取第一项
5. 已发送但Provider同步失败、UNKNOWN迟到成功、删除后回调：只补记录，保留事实，不重发、不复活

## 文件与接口

业务类型统一在 `mms/outgoing/OutgoingMmsModels.kt`，引用草稿/请求/attempt身份而非系统part伪身份。持久化在 `data/db/outgoing/` 和 `data/repository/OutgoingMmsRepository.kt`；附件、政策、PDU、Provider、协调器位于 `mms/outgoing/`；UI/ViewModel 分别在现有层。

### 任务1：草稿与持久发送日志

文件：新增 `data/db/outgoing/OutgoingMmsDatabase.kt`、`data/repository/OutgoingMmsRepository.kt`、`mms/outgoing/OutgoingMmsModels.kt`、Room schema；修改备份排除。

接口：`OutgoingMmsRepository` 暴露草稿 Flow，revision 条件更新；`accept(draftId, revision, snapshot)` 原子接纳并按 `(draftId, revision)` 去重；attempt包含token、事务、状态、回调事实与同步标记。不可变 `MmsSendSnapshot` 供任务2/3消费。每草稿最多10个附件，附件记录随草稿/冻结快照以显式 JSON 原子保存，保留独立附件ID、顺序、原件与准备字段；无需反射或独立附件表。另以 `callback_fact` 和唯一 `(attemptToken, fingerprint)` 保留不同迟到事实并去重相同回调。

- [x] 实现 schema1、显式实体和事务，不使用破坏性迁移
- [x] 实现草稿编辑、附件增删、接纳去重和文件引用，提交/取消CAS、迟到回调记录
- [x] 排除数据库/WAL/SHM与PDU的云备份及设备迁移；确认现有 .ptbackup 不含命令
- [ ] 编译和独立审查DB唯一约束/状态条件；runtime验证重复接纳、取消竞争、回调早到

### 任务2：附件、运营商政策与有界PDU

文件：新增 `mms/outgoing/MmsAttachmentPreparer.kt`、`MmsPayloadStore.kt`、`MmsSendPolicyResolver.kt`、`MmsSendPduComposer.kt`；必要vendor Composer与许可索引。

接口：`MmsSendPolicyResolver.resolve(subId): MmsSendPolicy`；`MmsAttachmentPreparer.import(uri)`和`prepare(original, policy, budget)`产出私有文件身份；`MmsSendPduComposer.compose(snapshot, transactionId): ComposedMms` 返回完整PDU与Provider同源part。

- [x] content URI有界复制、元数据净化、空间/数量/像素保护与取消清理
- [x] 静态图方向/尺寸/质量适配；动画不静默静态化，原件型内容超限阻断；非 UTF-8／含 NUL 纯文本及外部 SMIL 以明确提示的 octet-stream 原件保持 Provider/PDU 字节一致
- [x] 公开API读取订阅/配置并生成可比较政策身份；提交前重新核验
- [x] 单TO、UTF8主题正文、安全CID/位置、related与mixed区分、SendReq回读自检、完整PDU实际限额
- [x] 合成PDU解码与边界验证：中文emoji、多图/文件、限额与超1字节、动画、无效号码

### 任务3：Provider与持久发送协调器

文件：新增 `MmsOutgoingProviderWriter.kt`、`MmsSendCoordinator.kt`、`MmsSentReceiver.kt`、`MmsSendWorker.kt`、窄FileProvider路径；修改Manifest、AppModule与启动恢复。

接口：Provider writer 从同一 `ComposedMms` 准备/验证父子身份；协调器按requestId串行推进；Receiver只由token定位attempt并持久事实。

- [x] Provider头级Transaction-ID恢复、addr/part幂等补齐、源身份/内容回读验证、墓碑
- [x] PDU原子文件、提交前权限/角色/订阅/政策复核、DISPATCHING持久栅栏、平台调用最多一次
- [x] 显式mutable PendingIntent；解析受限SendConf，按设计区别拒绝/部分成功/畸形/平台空确认
- [x] Worker恢复只补本地阶段或转UNKNOWN；15分钟提示阈值、迟到回调、同SIM串行、成功后仅同步
- [x] 结束载荷撤权回收，未知载荷保留；文件引用/孤儿清理不删活跃或草稿文件
- [ ] runtime故障注入：进程重启、重复worker、callback早到、null cursor、角色丢失、ID复用与删除

### 任务4：编辑UI、外部入口与状态展示

文件：新增 `MmsComposerViewModel.kt`、`ui/message/mms/MmsComposer.kt`；修改ConversationDetailScreen、ComposeSmsActivity、消息状态投影和Manifest分享入口。

接口：UI通过Repository/协调器编辑和接纳；不直接访问SmsManager，不在ViewModel等待平台结果。独立编辑器与已有正文SMS路径清晰分流。

- [x] 单号码确认、附件/主题/模式、实时选SIM、准备/转换/错误、PDU大小、费用提示与草稿恢复
- [x] 接纳后只清空对应revision；失败保留；确定失败手动重试/未知另发提示
- [x] 外部SEND/SEND_MULTIPLE/SENDTO只预填并保留授权期间附件快照；全部多号码/CC/BCC阻断
- [x] 已有入站多地址不裁剪、不由首地址自动发MMS；新单人发送明确确认
- [x] 状态持久可见、草稿提示、删除墓碑；保持旧接收MMS完整展示与附件查看
- [ ] mock UI验证重复点击、旋转、离页、SIM更换、附件取消、深链接、多窗口与大字体

### 任务5：整合、安全与交付验证

文件：README/PRIVACY/AGENTS、许可配置、必要现有runtime验证脚本，设计状态和本计划。

- [x] 对照设计逐项审查，独立whole-branch review，修复高风险问题
- [x] `./gradlew :app:compileDebugKotlin :app:lintDebug :app:assembleDebug` 成功
- [x] 完整 `:app:assembleRelease` 与R8产物验证；不裁native库、不全局keep
- [ ] mock/platform boundary与现有接收/备份/vCard回归；记录实际设备/API/ABI、跑过与未跑项
- [x] `git diff --check`、暂存清单/隐私/Manifest/Room schema检查，本地提交与draft PR说明
- [x] 获发布授权后才push与draft PR，核对远端SHA并启动该SHA的CI；运行时结果见 PR #18，真实运营商验收另需明确许可

## 验证限制与开启门槛

本地mock与翻译模拟器不能证明运营商接受、计费或native ARM64真机适配。生产入口默认关闭，只有在目标运营商真机验收（明确SIM、收件号码、费用许可）后才调整默认构建配置；不把编译或API调用返回冒充已交付网络能力。

## 实施与验证记录（2026-10-02）

- 已实现并完成独立整分支安全评审；本地草稿的导入未完成原因、未知重发风险及恢复编辑来源均持久化，重开页面不会丢失风险确认
- Provider 局部写入可本地收尾；明确失败重试先同步旧结果并验证旧内容再切事务。旧尝试迟到成功阻止尚未提交的新尝试，并保留冲突事实
- 原件型附件先按实际字节保留预算，静态图分配剩余空间；预留保守协议开销，最后仍按完整 PDU 实长校验。正文/主题保留 UTF-8，文件文本仅在可无损往返时内联
- 纯文字分享仍走 SMS；会话附件入口带入正文，只有彩信持久接纳且原 SMS 输入未再改变才清空。返回或删除彩信草稿不会丢掉原 SMS 输入
- 仓库外合成 harness 已覆盖 PDU 编码边界、并发接纳与领取、未知结果禁自动重发、不同迟到事实、持久风险标记、13类回调解释，以及替身 Provider 的中断/冲突/删除恢复。生成 Room schema 已在 SQLite 验证唯一约束。这些不是 Android Provider 或运营商实测
- 本地 Kotlin/Java、Lint 与 Debug 构建已通过；默认关闭/受控开启两种完整 R8 构建均完成，入口修正后再次执行最终构建。具体最后提交的检查结果见对应 PR/CI，不能用旧提交构建代替
- 无 KVM 的 API35 软件模拟器尝试一次：ADB和ARM64转译可见，但启动未确认完成、APK安装超时；已停止，不声称设备/UI通过
- CI 保留默认关闭版的既有冷启动/备份/vCard回归，再构建受控开启版运行独立 MMS probe。probe 先确认虚拟机身份、SEND_SMS被拒绝及AppOps阻断，只插入 `sourceId=null` 的提交后合成状态，不构造可提交队列，不调用发信 API。它验证 Room→Receiver→Worker 和编辑入口，不能证明 SmsManager 网络提交、实际 TelephonyProvider 写入、MMSC互通或运营商接受
- 未验门槛继续保留：API31、实际 ARM64 Pixel、真实双卡/角色切换/APN/漫游、完整发送→独立接收端互通与计费链路。生产默认入口仍关闭

- PR #18 首次 CI 的两种完整 R8 构建与既有运行时回归通过；新增 MMS probe 在前置 AppOps 断言处停止。Android 15 权限同步显示 UID ignore、包 allow，需按有效 UID 优先语义判断；修正为仅接受明确 deny/ignore，并在 instrumentation 中再次验证实际模式，未放宽发送权限限制
