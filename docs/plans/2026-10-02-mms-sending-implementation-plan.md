# 单人 MMS 发送实施计划

> 实施方式：使用 Superpowers writing-plans 的接口与验收拆解，以及 executing-plans / subagent-driven-development 的分工、独立评审和完成前验证。项目规范优先：正式文件放 `docs/plans/`，不新增单元测试，不机械重复已获授权的设计审批；独立模块可以并行实现，共享 Gradle 构建串行执行。

> 当前状态：单人 MMS 发送已实现并在普通构建开放，不再使用发送构建开关。下方任务清单保留当时的实现与验证拆分，未勾选的验证项不表示对应功能仍被禁用。

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
- 普通构建开放发送入口；回调恢复始终启用

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

实际接口：`MmsSendPolicyResolver.resolve(subId): MmsSendPolicy`；`MmsAttachmentPreparer.importAttachment(uri)` 和 `prepare(attachment, policy, budget)` 产出私有附件记录；`MmsSendPduComposer.compose(snapshot, transactionId): ComposedMms` 返回完整 PDU 与 Provider 同源 part。

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

当前接口：UI 通过 Repository／协调器编辑和接纳，不直接访问 SmsManager，不在 ViewModel 等待平台结果。后续[统一编辑器](2026-10-03-unified-message-composer-design.md)已替代独立工程表单：`MmsComposerDialog` 仅作为宿主，内部复用 `MessageComposer`；正文无主题和附件时走 SMS，其余走 MMS。

- [x] 单号码确认、附件／主题、实时选 SIM、准备／转换／错误、费用提示与草稿恢复；当前准备和 PDU 大小校验自动执行，不再提供显式协议切换及手动准备表单
- [x] 接纳后只清空对应revision；失败保留；确定失败手动重试/未知另发提示
- [x] 外部SEND/SEND_MULTIPLE/SENDTO只预填并保留授权期间附件快照；全部多号码/CC/BCC阻断
- [x] 已有入站多地址不裁剪、不由首地址自动发MMS；新单人发送明确确认
- [x] 状态持久可见、草稿提示、删除墓碑；保持旧接收MMS完整展示与附件查看
- [ ] mock UI验证重复点击、旋转、离页、SIM更换、附件取消、深链接、多窗口与大字体

### 任务5：整合、安全与交付验证

文件：README/PRIVACY/AGENTS、许可配置、设计状态和本计划。

- [x] 对照设计逐项审查，独立whole-branch review，修复高风险问题
- [x] `./gradlew :app:compileDebugKotlin :app:lintDebug :app:assembleDebug` 成功
- [x] 完整 `:app:assembleRelease` 与R8产物验证；不裁native库、不全局keep
- [ ] mock/platform boundary与现有接收/备份/vCard回归；记录实际设备/API/ABI、跑过与未跑项
- [x] `git diff --check`、暂存清单/隐私/Manifest/Room schema检查，本地提交与draft PR说明
- [x] 获发布授权后才push与draft PR，核对远端SHA并启动该SHA的CI；运行时结果见 PR #18，真实运营商验收另需明确许可

## 当前实现与验证边界

单人发送、持久草稿、附件准备、平台提交、回调恢复及状态展示已实现。普通构建开放发送入口；不存在默认关闭版或受控开启版的发布流程。

当前 CI 仅保留 Release 混淆后的 vCard 解析和 TensorFlow Lite 原生库初始化检查，参见 [运行检测说明](../../.github/runtime/README.md)。MMS probe、冷启动、备份和界面回归脚本已删除，不作为本功能的持续检查要求。

历史 Kotlin、Lint、Debug/Release 构建记录不代表当前所有设备和运营商场景已经验证。API 31、实际 ARM64 Pixel、双卡/角色切换/APN/漫游，以及完整发送到独立接收端的互通和计费链路，仍须以对应设备与运营商的实测结果为准。
