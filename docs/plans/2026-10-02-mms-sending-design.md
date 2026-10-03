# MMS 发送能力设计

状态：单人发送范围与方案已获用户确认（2026-10-02），进入实施；生产发送入口在系统链路和目标运营商验收前默认关闭。

代码基线：`main` 的 `09ce5f4d03ae1785a496b8ee833f92c00dea0f05`（2026-10-02，已包含 PR #17 的 Release/R8 修复）。本文按 Superpowers brainstorming 的架构设计路径审查现状、比较方案、定义边界和验收条件；目录、中文文档及验证方式遵循 [AGENTS.md](../../AGENTS.md) 和[编码与验证规范](../development/coding-and-validation.md)。设计审阅通过后按[实施计划](2026-10-02-mms-sending-implementation-plan.md)推进实现和验证。

## 1 目标与范围

用户已说明：完整 MMS 接收、解析和展示已经过一段时间日常使用，当前希望补齐 MMS 发送。本方案的目标是让用户在 PixelText 中编辑彩信、选择 SIM、确认附件和发送，并在离开页面或进程重启后仍得到可信结果，同时不破坏现有 SMS 与 MMS 接收。

成功标准不是“调用发送 API 没有抛异常”，而是：发出的是用户确认的内容和 SIM；平台返回结果被可靠记录；系统消息、应用镜像和界面最终一致；失败可恢复，结果未知不自动重复发送；附件、地址和时间经对照客户端验证。

### 已确认的发送范围

用户已确认本次只做单人发送，并指出群组会影响既有会话分组规划，后续也很可能不支持群组。因此，新增发送命令只接受一个明确的电话号码；不把群组写成后续交付承诺，不为群发或群会话预建模型、接口、数据库字段或界面。

本次不改变之前的会话分组规划。已有 MMS 接收、完整多地址解析与保存、镜像、展示能力继续保留；单人发送限制不能用来裁掉历史消息中的其他地址。其后用户已审阅本方案并明确要求继续下一步，实施范围仍保持单人发送。

### 本次采用的功能范围

本次按下列附件、编辑与恢复行为实施：

- 在已确认的**单个电话号码收件人**范围内，建议支持多种附件类型。含正文、可选主题（不能单独发送空消息）、单张/多张图片、体积合规的音视频、名片、日历及普通文件；“可选择”不承诺所有运营商或接收终端都支持任意 MIME。
- 图片提供本地适配；音视频及文件先支持合规原件发送，超限明确阻止。音视频自动转码和剪辑作为独立后续能力，不把“能播放”当作“能压缩到彩信大小”。
- 发送入口包括会话内附件按钮、外部分享至 PixelText、`sms/smsto/mms/mmsto` 编辑入口。外部 Intent 只预填草稿，不能直接启动发送。
- 无附件、无主题且没有显式选择彩信的单人正文继续走 SMS；长短信不因字符数而静默切为 MMS，附件失败不静默删附件改发 SMS。选择 MMS 后明确显示“彩信”和所用 SIM。
- 保存 MMS 草稿，支持恢复编辑、发送前取消、确定失败后手动重试；提交给系统后不能承诺撤回。
- 多人 MMS、逐人群发和 CC/BCC 不在本次范围，也不设后续路线图。本草案另不包含邮件地址收件人、RCS、定时发送、拍摄/录音编辑器、富 HTML/SMIL 编辑器或 MMS 备份格式扩展。

附件类型、原件限制和转换结果必须明确展示，不做隐藏降级。无需再次选择单人或多人范围。

## 2 应用整体审查与复用边界

这次审查覆盖与发送有关的系统入口、Compose 页面、ViewModel、Provider、镜像、附件解析/展示、后台恢复、许可、备份及 Release 配置；没有把它扩大为无关功能重构。

| 现状与证据 | 对发送设计的影响 |
| --- | --- |
| [ComposeSmsActivity](../../app/src/main/java/vip/mystery0/pixel/text/ComposeSmsActivity.kt) 只解析单地址和正文；[会话页面](../../app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationDetailScreen.kt) 是文本输入框，点击后立即清空 | 新增草稿和附件准备状态，只有持久接纳本次发送后才清空对应版本；不能丢失并行新输入 |
| [ConversationDetailViewModel](../../app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailViewModel.kt) 直接写 SMS 占位、动态注册回调并在 `viewModelScope` 等待 | 不复制为 MMS 实现；发送生命周期独立于页面，避免离页或进程死亡丢失结果 |
| [SimInfoProvider](../../app/src/main/java/vip/mystery0/pixel/text/util/SimInfoProvider.kt) 可获取激活卡；页面通过 `remember` 缓存并可退到第一张卡 | MMS 必须实时复核订阅；没有明确有效选择时要求选卡，不能静默换卡 |
| [MmsReceptionResponseSender](../../app/src/main/java/vip/mystery0/pixel/text/mms/MmsReceptionResponseSender.kt) 已调用 `sendMultimediaMessage` 发接收协议响应，有私有日志和自动重试 | 只借鉴平台入口、文件授权和日志理念；不能复用其重试策略或无正文编码器发送用户彩信 |
| [vendor/pdu](../../app/src/main/java/vip/mystery0/pixel/text/mms/vendor/pdu) 已有 `SendReq`、`SendConf`、`PduBody`、`PduPart` 和解析器，尚无 `PduComposer` | 优先同源补齐最小 Composer，避免另引整套 MMS 网络栈或调用隐藏 API |
| [MmsProviderWriter](../../app/src/main/java/vip/mystery0/pixel/text/mms/MmsProviderWriter.kt) 只接受 NotificationInd→RetrieveConf 转换 | 不往接收 writer 塞发送分支；独立发送 writer，有限复用安全 part 写入规则 |
| [镜像模型](../../app/src/main/java/vip/mystery0/pixel/text/domain/model/mirror/MirrorMessageModel.kt) 保留箱类型、全部地址和 part；[toMessageModel](../../app/src/main/java/vip/mystery0/pixel/text/data/repository/mirror/MessageMirrorRepositoryImpl.kt) 丢弃发送细节并取首个对方地址 | 增加明确的发送状态展示模型；完整消息仍经 Provider 回读，不能把执行日志冒充镜像消息 |
| [MmsContentModel](../../app/src/main/java/vip/mystery0/pixel/text/domain/model/mms/MmsContentModel.kt)、[MmsContent](../../app/src/main/java/vip/mystery0/pixel/text/ui/message/mms/MmsContent.kt) 已覆盖图文、音视频、HTML、名片、日历、文件和演示页 | 已发送内容复用完整展示；编辑预览单独用草稿身份，不能伪造 `MmsPartKey` 的系统 part ID |
| [MessageMirrorWorker](../../app/src/main/java/vip/mystery0/pixel/text/worker/MessageMirrorWorker.kt) 有初始化和 dirty 队列早退 | 新建发送恢复 Worker；不能让发送依赖“镜像刚好有脏记录” |
| 镜像 Room 为版本 4；[备份格式](../development/backup-format.md) 仅包含白名单 SMS 快照，备份容器内镜像版本单独固定为 3 | 发送使用独立数据库，避免为执行态修改镜像 schema/备份协议；升级后仍不得恢复自动发信任务 |
| minSdk 31、target/compileSdk 37、JDK 21、仅 ARM64；Release 开启 R8，PR #17 刚修复反射问题 | 最终必须验证完整混淆 Release 与真实设备，不能只看 Debug 或裁掉 native 库的模拟包 |

[完整接收设计](2026-09-07-mms-reception-design.md) 和[消息镜像设计](2026-09-06-message-mirror-design.md) 是背景；本节结论以当前代码为准。通知快捷回复和通话快速回复仍是现有 SMS 入口，本次不顺带重写整个 SMS 发送栈，但相关 Manifest 资格与纯文本路径必须回归。

## 3 方案比较与建议

| 方案 | 收益 | 代价与结论 |
| --- | --- | --- |
| 系统 MMS 传输 + 独立持久发送协调器 + 当前 Provider/镜像 | SIM、APN、运营商网络由平台处理；复用已稳定的消息展示；执行态可恢复 | 需要 Composer、草稿/请求日志、明确状态机；**推荐** |
| 沿用 SMS ViewModel 发送模式，复用接收响应 sender | 初始代码较少 | 页面生命周期、回调 extras、自动重试和协议体都不匹配；容易把“未知”变成重复收费，否决 |
| 第三方全套 MMS 库或自行 HTTP 请求 MMSC | 可以控制更多协议细节 | 重复 APN、代理、移动网络与运营商服务适配；扩大网络/许可/维护面；当前公共 API 足够，否决 |

只引入必要的 AOSP Composer 源码，不引入 `PduPersister` 和旧 `TransactionService`。网络边界保持为用户主动发送所需的 Android/运营商链路；不增加消息云上传、远程压缩或解析。

## 4 组件职责与数据流

拟新增组件名称用于确定边界，最终文件拆分应按相邻代码规模保持简洁。

| 组件 | 输入和输出 | 责任 |
| --- | --- | --- |
| `OutgoingMmsRepository` 与 `MmsComposerViewModel` | 草稿 ID、版本、编辑命令 → `StateFlow` | 持久草稿、准备状态、校验提示、接纳发送；UI 不直接调 SmsManager |
| `MmsAttachmentPreparer` / `MmsPayloadStore` | 用户授权 URI → 私有附件快照 | 有界读流、MIME 校验、图片适配、取消、原件与发送副本分离 |
| `MmsSendPolicyResolver` | 明确 subId → 运营商配置快照 | 可发送性、字节/尺寸/主题限制；选择变更使旧准备结果失效 |
| `MmsSendPduComposer` | 不可变发送快照 → 二进制 PDU | WSP/PDU 编码、SMIL/CID 生成、总字节校验；不访问网络 |
| `MmsOutgoingProviderWriter` | 不可变快照、已有源 ID → Provider 行和子项 | 可恢复持久化、只更新本请求拥有的行；不分发网络请求 |
| `MmsSendCoordinator` / `MmsSendWorker` | 请求 ID → 可恢复阶段推进 | 串行领取、提交栅栏、处理结果、落库补偿、启动恢复；无自动网络重发 |
| `MmsSentReceiver` | 显式 PendingIntent、结果码和受限响应字节 | 短时持久保存回调事实，再交协调器补写 Provider/镜像 |
| `OutgoingMessagePresentation` | Provider 镜像 + 与源身份匹配的发送日志 | 展示发送中/失败/未知/成功；不制造第二份持久消息 |

完整路径：编辑草稿 → 导入并准备附件 → 校验选卡/配置 → 原子接纳不可变发送快照 → 创建或恢复 Provider 暂存消息 → 编码并验证 PDU → 持久记录提交意图 → 调用系统 → 持久回调事实 → 更新原 Provider 行 → 定向 dirty → 镜像回读 → 复用现有 MMS 展示与检索。

Koin 在 [AppModule](../../app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt) 注册应用级仓库和协调器。等待平台结果不占住一个协程或 Worker 15 分钟；Worker 推进到提交即结束，由 Receiver 和延迟恢复唤醒继续。发送调度不带普通互联网“已连接”硬约束，因为 MMS 可能使用平台建立的专用移动网络；是否可用交给订阅配置和平台结果。

## 5 草稿与持久执行状态

### 私有数据库与文件

新增独立 Room 数据库 `outgoing_mms.db`，首版 schema 1，导出 schema；后续升级必须显式 Migration，禁止破坏性重建。它是命令与执行事实库，不是 Telephony 消息镜像。

核心记录：

- `draft`：`draftId`、编辑版本、单个规范化 `recipientAddress`、正文、主题、显式 SMS/MMS 模式、选定 subId、创建/更新时间。页面 SavedState 只保存 ID；正文和文件不塞进 Intent 或 WorkManager Data。
- `draft_attachment`：稳定附件 ID、顺序、原 MIME/展示名、私有原件身份、准备版本、发送副本 MIME/长度/哈希、转换说明、可见错误。导入失败项仍可删除或重新选择。
- `send_request`：UUID、唯一 `(draftId, draftRevision)`、不可变内容快照、单个 `recipientAddress`、固定 subId、配置版本、源 MMS ID、源行身份校验值、持久化阶段和删除墓碑。
- `send_attempt`：请求 ID、序号、唯一 attempt token、独立 Transaction-ID、PDU 文件身份/长度/哈希、提交时间、阶段、结果码、HTTP 状态、受限 SendConf 字节/解析结果、Provider 待补写标记。一个请求同一时刻只能有一个可提交 attempt。
- `file_cleanup`：只包含本功能拥有且已无引用的私有路径，清理失败可重试。

源附件放 `noBackupFilesDir/mms-outgoing/`，随机内部名称与原展示名分开；发送 PDU 放专门 `filesDir/mms-send-pdu/`，由独立窄范围 FileProvider 暴露。两者不能放可随时被系统回收的 cache 目录。数据库、WAL/SHM 及 PDU 目录显式加入云备份和设备迁移排除；不依赖“已有 root 排除大概覆盖了所有 domain”。

### 编辑和提交一致性

- 草稿编辑使用 revision 乐观并发控制。附件选择、转换或返回页面的迟到结果只更新匹配 draft/revision 的记录；换 SIM、删除附件或关闭草稿不能被旧任务复活。
- 文本短时节流保存，发送、退出编辑与切会话前刷新持久化；正在导入的项目显示准备中。无法保证操作系统强杀前最后一次未提交键入，不能虚称每个键都已落盘。
- 只有当前版本所有附件可用、政策校验通过，才能在一个数据库事务内创建发送请求并锁定该快照。双击、旋转、多窗口重复命令返回同一个 requestId。
- UI 收到“已接纳到本地发送队列”后才清空该草稿版本，并新建下一份草稿；这不等于网络发送成功。落盘失败保留输入和附件。
- 草稿与发送请求独立持有文件引用。用户编辑下一条消息、删除原草稿或图库原文件，不能改变已经确认的发送载荷。
- 草稿默认保留至用户发送或明确删除，不因清缓存或升级静默丢弃。列表显示本地草稿提示；它们不会因为存在草稿而自动联网，也不自动变成 SMS。

## 6 附件准备和费用可见性

图片/视频使用系统 Photo Picker；API 31 等设备由 Activity Result 合同的系统文件选择回退支持。其他文件使用 SAF `ACTION_OPEN_DOCUMENT`/`CATEGORY_OPENABLE`。不为附件增加全相册、文件系统、定位、录音或相机权限。[Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) 和 [SAF](https://developer.android.com/training/data-storage/shared/documents-files) 只授予所选文件；外部分享授权不保证可持久保留，必须在授权有效时流式复制到私有区。

### 输入防护

所有外部 URI、MIME、展示名、文件大小都不可信：只接受可读取的 `content://` 文件，不跟随网页地址下载、不接收任意 `file://` 路径、不转授外部 URI。元数据大小未知时按实际流字节限额，超限立即停止，不读完整巨型文件后才报错。文件名去控制字符且有长度上限，只用于显示；磁盘路径、CID 和 Content-Location 由本应用生成。

本草案建议的资源防护值是产品工程上限，不是运营商能力：每草稿最多 10 个附件，单次导入原件最多 32 MiB，单草稿原件总量最多 64 MiB，图片最多 64 百万像素并使用目标尺寸采样解码。拒绝超过预算的输入，不静默只取前几个。最终可发送字节还要受到更小的运营商上限约束。

默认保留原件与发送副本，预览显示实际发送副本。对静态图片根据当前卡宽高/字节预算处理方向、缩放和有限次数质量调整，必要时转 JPEG；透明图转 JPEG 前显示背景变化。转码副本不继承 GPS 等 EXIF 元数据。GIF/动态 WebP 不静默变成首帧；符合预算时可发送原件，否则用户移除/换文件，静态化须另有明确操作。原件型音视频、名片、日历和文件不改写内容，并提示文件可能保留原始元数据。系统 Provider 强制将 `text/plain` 与 `application/smil` 存入文本列：可无损 UTF-8 往返的纯文本原件标记 UTF-8；非 UTF-8／含 NUL 的纯文本和外部 SMIL 保留原始字节，以 `application/octet-stream` 普通文件发送，并在准备提示中明确类型变化，避免编码损失或外部演示控制。

### 预算和转换规则

1. 按所选 subId 读取有效配置，检查 `MMS_CONFIG_MMS_ENABLED`、最大消息字节、最大图片宽高、主题长度和相关文本限制。缺失值不得直接按 `Bundle.getInt` 的零解释为真实限制；配置缺失/不可读单独显示，发送保持阻断并可重新读取，不编造所有运营商统一 300 KB 的承诺。
2. 先计算文本、主题、SMIL、地址和协议头开销，再给媒体分配预算。图片均分剩余预算后可让未用余额供其他图片使用，顺序可预测；达到尺寸/质量底线仍超限时阻断，不能静默移除内容。
3. 最终以**完整编码 PDU 的实际字节数**作为发送门槛，附件大小相加不等于彩信大小。总 PDU 另设 10 MiB 防御性上限，取其与运营商上限的较小值。
4. SIM 或运营商配置改变后使旧预算失效，重新准备并展示内容变化；如果用户已经提交，提交前最后一次复核发现变化则暂停要求重新确认，不能在后台改质量、改附件或改卡。
5. 音视频/文件超限明确显示大小和限额。后续若增加 Media3 Transformer，要单独定义码率、时长、HDR→SDR、音轨、取消与硬件编码失败处理；[Transformer 依赖设备 MediaCodec 能力](https://developer.android.com/media/media3/transformer/supported-formats)，不是“已经引入 Media3 播放器就能保证压缩”。

输入区持续显示“短信/彩信”、SIM、已准备附件数量和总 PDU 大小；首用彩信和手动重发结果未知消息时提示可能产生运营商费用。不得承诺彩信免费、走 Wi-Fi 必然免费或猜测套餐价格。

## 7 PDU 与平台传输

### 可复用源码与编码

现有 vendor 来源是固定 AOSP 提交 `1cdfff555f4a21f71ccc978290e2e212e2f8b168`，详见[许可索引](../licenses/README.md)。本次已通过[AOSP官方GitHub镜像](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/telephony/common/com/google/android/mms/pdu/PduComposer.java)核对该固定提交的 `PduComposer`（blob `7af0d1b09eed0585ea3718ba51176bc94f8844d3`）。后续只补最小Composer，保留原许可头并记录改动；在应用命名空间编译公开源码，不反射系统隐藏类，不覆盖当前已加固的parser。

发送 PDU 明确包含 `M-Send.req`、MMS 版本、随机唯一且可持久恢复的 Transaction-ID、FROM insert-address-token、唯一 TO 收件地址、日期、可选主题和正文、内容类型及 part。TO 数组必须恰好一项，CC/BCC 为空；应用领域模型保留单个 `recipientAddress`，只有编码边界按 PDU API 构造单元素数组，不引入群收件人抽象。FROM 不依赖读到本机号码，也不把另一张 SIM 的号码填进去。禁用默认请求阅读报告；投递报告先保持关闭，若未来加入开关必须依据运营商能力，且“平台已发送”与“对方已送达/已读”分开。

正文使用 UTF-8；part 具有明确 MIME、字符集、顺序、稳定 CID/Content-Location 和安全协议文件名。多媒体演示使用受限生成的 SMIL 与 `multipart.related`，首 part 为 SMIL且start/CID一致；不接收外部SMIL控制要发送的文件。纯文件集合使用平面 `multipart.mixed`，无需伪造SMIL；普通附件必须保留在multipart和附件列表。上游Composer会为首part写type/start参数，实施时须核对并对mixed与related做必要的最小条件化修正、记录源码差异。发送模型限定平面结构，不假定Composer会递归编码接收器新增的 `PduPart.children`。正文显式选MMS时创建真实文本part；主题为空体时阻断并提示添加正文或附件，所有可提交消息至少有一个有效内容part。

上游有宽松收件人检查、直接访问空字符串首字节、无界流读取等假设；包装层必须先验证恰好一个有效电话号码收件人、非空事务/MIME/CID、字符串终止与长度，Composer内补预算和混合体参数条件，所有charset只使用已验证可编码值。不得把其注释“构造器已验证必需字段”当作实际保证。

编码完成后用现有有界 parser 回读，核对类型、事务、地址、主题、part 顺序/MIME/字节；这一自检只能发现内部不一致，不能替代独立 PDU 实现或对照客户端互通。Composer 若使用 `ByteArrayOutputStream`，必须在读取附件之前和写出过程中执行总预算，不能先分配无界内存。

### 系统负责的边界

使用当前有效 `SmsManager.createForSubscriptionId(subId)` 调用公共 `sendMultimediaMessage(context, pduContentUri, null, null, sentIntent)`；locationUrl 为 null，让平台按该订阅处理 MMSC/APN，不把收到的彩信 Content-Location 当发送地址，不开放任意 MMSC URL。正常生产发送不传覆盖运营商限制的 configOverrides。

平台负责运营商服务委派、MMS 网络/APN/代理、HTTP 交互与其内部重试。应用不请求 `WRITE_APN_SETTINGS`、不直接读改 APN、不切换默认数据卡、不强开数据/漫游。`SEND_SMS`、默认短信角色、有效订阅和设备消息能力在准备前与提交前再次核对。默认短信角色用于本应用完整管理 Provider 的产品前提，不把它错误描述为公共发送 API 的唯一权限条件。能力判断按API版本处理：API33及以上检测 `FEATURE_TELEPHONY_MESSAGING`；该常量从API33才提供，API31–32使用既有 `FEATURE_TELEPHONY`、实际SmsManager/权限/有效订阅并处理 `UnsupportedOperationException`，不能让最新版feature检查误拦Android12。

双卡选择按“用户本次明确选卡 → 有效会话选择 → 有效系统默认短信卡 → 唯一激活卡”解析；多卡且无明确默认时让用户选择。冻结后的 subId 失效就暂停，不改成默认卡或卡槽 0。`READ_PHONE_STATE` 丢失与“没有 SIM”分别显示。订阅与载入配置的变化要可观察，不沿用页面一次性 `remember` 快照。

MMS 专用数据能力可能与普通移动数据开关、默认数据卡、Wi-Fi、漫游和 IMS 配置交互；界面给出平台返回的可行动原因，不把这些条件用猜测的本地网络判断统一拦截。

### URI 与 PendingIntent

新建 `${applicationId}.mms.send` FileProvider，`exported=false`、`grantUriPermissions=true`，只映射 `mms-send-pdu/`。只给系统实际需要读取的该条 PDU 读权限，不暴露附件原件目录、不授写权限，不扩大为 `<root-path>`。[FileProvider 安全说明](https://developer.android.com/privacy-and-security/risks/file-providers) 是路径范围底线。

平台的 URI 转授权和 carrier app 委派以 Android/AOSP 契约为主，真机验证实际授权链；不把现有硬编码 `com.android.phone` / `com.android.mms.service` 双包授权当作通用跨版本契约。

发送回调使用 Manifest 中 `exported=false` 的显式 `MmsSentReceiver`，固定 action/component 与唯一 `pixeltext://mms-send/<attemptToken>` data。使用 **FLAG_MUTABLE** 让平台填入 `SmsManager.EXTRA_MMS_DATA` 和 `EXTRA_MMS_HTTP_STATUS`；FLAG_IMMUTABLE 会忽略这些 fill-in extras，不能照搬现有接收响应 回调。token 从不可被填入覆盖的原始身份解析，并与持久 attempt、subId、当前阶段核对。Receiver 不接收外部提供的文件路径，也不因未经关联的广播创建消息。

## 8 Provider 持久化与镜像一致性

### 不把平台 pending 队列当应用发件箱

AOSP TelephonyProvider 在 `M-Send.req` 进入 OUTBOX 时会创建 `pending_msgs`；其他默认短信应用可能扫描并重试。公共 `sendMultimediaMessage` 输入是 PDU URI，不要求应用先创建 OUTBOX 行。为减少换默认应用时重复发送风险，采用以下映射：

| 私有发送状态 | Provider 消息箱 | PixelText 显示 |
| --- | --- | --- |
| 编辑草稿，尚未接纳发送 | 暂不创建 Provider 行 | 本地草稿 |
| 准备 Provider、等待提交、平台处理中 | `MESSAGE_BOX_DRAFTS` | 准备中/发送中，由本请求日志覆盖普通草稿外观 |
| 平台确认成功 | `MESSAGE_BOX_SENT` | 已发送；不表示送达 |
| 确定失败、结果未知、已取消提交 | `MESSAGE_BOX_FAILED` | 失败/待确认/已取消，由日志精确区分 |

MMS 公开存在 `MESSAGE_BOX_FAILED=5`，使用 `Telephony.Mms` 的常量更新已有 URI；不虚构 `content://mms/failed` 插入路由。PixelText 的可见“待发送”列表来自私有队列，不创建可被其他应用自动消费的系统 OUTBOX。切到其他短信应用时，在途消息可能暂显示草稿；这是明确的兼容性取舍。其他应用或用户主动重发、厂商私有机制、运营商内部重试仍不受本应用控制，不能承诺全链路恰好一次。

### 写入顺序与崩溃恢复

1. 先持久 request/attempt 与随机 Transaction-ID；再以 DRAFTS 插入 `m_type=SEND_REQ`、`sub_id`、通过 `Telephony.Threads.getOrCreateThreadId(context, setOf(recipientAddress))` 解析的单人 `thread_id`、MMS 秒级日期、`read=1`/`seen=1`、主题与字符集、`tr_id`、内容类型等必要字段。这里只为明确的单人发送解析系统线程，不借用包含该号码的多方线程，也不改变现有列表的会话分组规则。
2. 返回的源 ID立即记录。若插入后、记录 ID前崩溃，先用父行插入时一并写入的唯一Transaction-ID、订阅、PDU类型等头字段查找候选，Provider 维护的creator可作为补充身份依据；不能要求尚未写入的地址/part也完整匹配。头级候选0且完整查询成功才可补建；1个进入PREPARING恢复，核验已有子结构再补齐；多于1个或查询失败停止并报告，不盲插或按正文去重。
3. 向该父行写 addr 与 part。源文件名不作为 Provider 存储路径；先安全插入再回写展示元数据，复用接收 writer 已验证的做法。每个part先分配本请求独有、可重建的CID和顺序；恢复时按已确认父行及这些标识查找，覆盖part插入成功但childId尚未记账的窗口。单一匹配可以重写未完成流，多候选或不符预期的子结构停止；addr 按冻结的唯一 TO 和必要 FROM 核对，拒绝新增 CC/BCC 或第二个收件地址，避免重插相同地址。只在PREPARING且父行所有权明确时补齐/清理本请求子项，绝不清空另一条消息或已提交的结构；外部编辑冲突不默默覆盖。
4. Provider 和 Room/文件不是单一事务。持久分阶段日志，完成后回读头、地址和全部 part，核对字节与哈希，再置 `providerReady`。任何失败只做本地恢复，不能继续网络发送。
5. 最终 PDU由同一冻结快照生成并落盘，fsync/原子替换后记录身份；提交前验证文件及 Provider 仍是本请求的源行。镜像可先读到准备中的行，但 UI 的状态投影必须显示准备中，不能误当已发送或展示残缺为最终内容。
6. 回调先落私有事实，再幂等更新该源行的箱类型、`resp_st`、可用 `m_id` 和时间。`st` 是 MMS 协议状态，不塞应用自定义数字。发送时间以系统秒单位保存，不能套用 SMS 毫秒字段。
7. 标记当前 MMS dirty，走现有镜像/附件复制/文本索引；重复 dirty 和重复回调只能更新一条消息，不新建 sent 行。发送成功而 Provider 更新失败显示“已发送，记录同步中”，只重试持久化，不再次调用网络。

每次更新、删除或迟到回调先核对源 ID和事务/订阅/内容身份，防止系统数据库重建或 ID复用后误改新消息。Provider 查询 null、权限丢失、读取异常都不是“消息不存在”。只有确认删除才能设置墓碑；回调永不复活被删除的消息。

## 9 状态机和重复发送防护

### 状态与恢复规则

| 阶段 | 可继续的本地动作 | 网络动作与用户语义 |
| --- | --- | --- |
| `PREPARING` | 附件/Provider/PDU幂等准备 | 尚未交系统，可取消 |
| `READY` | 复核权限、SIM、配置、文件和墓碑 | 数据库比较并更新后仅一个领取者可进入提交 |
| `DISPATCHING` | 已持久记录 attempt 和提交意图 | 从这里起可能已经调用系统，进程重启不能自动再调用 |
| `AWAITING_RESULT` | 等 Receiver，安排低频状态恢复 | 方法返回只进入等待回调，不证明平台已接纳或发送 |
| `SENT` | 补写 Provider/镜像、收尾 | 已确认平台成功，不自动重发 |
| `FAILED` | 保留内容、显示具体原因 | 手动重试创建新 attempt；不自动重发 |
| `UNKNOWN` | 接受仍匹配的迟到回调、补写保守消息箱 | 提示可能已经发出；重发需要明确重复/费用风险确认 |
| `CANCELLED` | 清理未提交载荷、保留/删除草稿按用户动作 | 只用于提交前真正取消，不用于已交系统的请求 |

接纳命令与 WorkManager unique work 都不是网络幂等保证。应用在调用 Binder 前先记录 `DISPATCHING`，调用后按条件更新 `AWAITING_RESULT`；回调可能早于方法返回，终态不能被后续“等待”覆盖。重启发现 `DISPATCHING` 无法区分“调用前崩溃”和“调用后崩溃”，宁可标未知，也不自动补发。

同一 SIM 的应用提交串行领取，其他 SIM 可独立处理；没有全局无限队列。强行停止应用、系统后台限制或通知权限关闭可能延迟处理/提示，下一次启动会恢复状态，不承诺实时后台回调或弹窗。延迟恢复只能推进本地阶段和检查超时，Worker 重跑、`Result.retry()`、进入前台、网络恢复、重启、角色恢复都不能把已提交 attempt 再交系统。平台内部重试不由应用增加第二层自动重试。

草案采用 15 分钟作为首次“结果待确认”用户提示阈值，它不是平台传输的强制截止时间。仍接受迟到回调；不因超时立刻删除平台可能读取的 PDU、不声称已取消系统请求。

### 回调解释

- 保存 `resultCode`、HTTP 状态和受限响应数据，区分运输结果、MMSC响应、Provider同步与镜像状态。
- `Activity.RESULT_OK` 不能单独覆盖一个有效SendConf的拒绝：AOSP HTTP路径收到响应即可返回该码。合法、事务匹配且ResponseStatus=0x80才记为MMSC已接受；如果完全没有SendConf，因运营商服务合法允许null，记为SENT且 `confirmation=platform_only`，不伪造 `m_id` 或送达信息。有响应字节时必须检查类型、事务与状态，不能把别条消息的响应拿来应用。
- FAILED仅用于本地提交前可证实尚未调用系统的失败，以及合法、事务匹配且非部分成功的MMSC拒绝。首版对其余非OK平台回调保守进入UNKNOWN，同时显示SIM/网络/APN等具体错误类别；HTTP、I/O、通用/新错误码、Binder提交边界异常、超时和回调缺失不能证明MMSC未接受。不把某个最终错误码推断成“此前没有carrier委派/平台内部重试”，以后若缩窄UNKNOWN必须有对应平台路径证据。
- 存在但畸形、类型错误或事务不匹配的响应进入UNKNOWN，并保留“系统返回成功，但彩信确认异常”的事实，不能自动重发。有效匹配的拒绝SendConf记FAILED，即使运输层为RESULT_OK；部分成功0xC4单列结果不完整并禁止整体重试。非OK平台码却出现有效成功SendConf等互相矛盾结果记UNKNOWN，不伪造运营商Message-ID。
- 原始 MMSC 响应文本不直接作为 Snackbar，不写日志；只映射经过审核的类别。报告只能用已关联的 `m_id + subId + recipients` 处理，复用现有 `MmsReceptionReports` 的防误关联思路。

明确失败后用户手动重试可沿用同一逻辑消息和Provider 行，但每次有新attempt/token/Transaction-ID。先持久旧/新事务映射，再以预期旧tr_id和已确认的源身份为条件，把本请求FAILED行改为DRAFT、写新tr_id并清除旧m_id/resp_st；恢复允许识别已记录的转换前后两态，核验完成才进READY。不可变身份不包括可变化的msg_box、响应头或完成时间，避免Provider 已成功更新而日志未更新时误判冲突。迟到旧回调先归属旧attempt，不能覆盖新attempt的关联字段；若旧attempt出现意外成功，保留该事实并提示可能重复，不谎报新attempt失败或撤回。对于 `UNKNOWN`，用户选择“仍然重发”创建新的逻辑请求和 Provider 行，并保留 `possibleDuplicateOf`；原请求若后来成功，二者都如实显示，不能将两次可能收费的发送藏在同一气泡里。

### 取消、删除、角色变化与回收

- 提交前取消与领取共享数据库状态比较条件，谁先成功决定结果；取消赢则绝不调用系统，提交赢则提示不能撤回。
- 提交后删除消息只删除本地记录/Provider展示，可能仍发送；确认后设置墓碑，回调只记录收尾，不重新创建消息。
- 丢失默认角色/权限立即停止尚未提交的工作；已提交回调仍尽力保存私有事实。不能写 Provider 时留补写标记，获得权限后只补记录。AOSP完成时的持久化流程可能重新判断默认应用；角色在途变化时，平台可能另写一条Provider 消息。不能承诺跨角色切换绝不出现副本：恢复后按事务、subId、完整受众、内容身份核对，不依赖非公开回调 URI、不按正文/时间相似自动删合并。不能可靠唯一关联时保留差异并提示，不再次提交网络。
- 已确认结束的 PDU撤销 URI读授权并删除；重试所需的附件私有快照保留到用户删除失败消息或编辑后发送完成。Provider/镜像已完整且无草稿/重试引用时回收冗余发送副本。
- 未确认结束的PDU不按普通cache策略清理，保留至迟到回调或用户明确清理。重启只可能改变本地授权/执行环境，不能证明carrier服务没有持久委派或MMSC尚未接受，不作为发送完成依据。清理操作说明它不能撤回已经发出的内容；即便释放文件也保留UNKNOWN事实，不变成可自动重试。限制遗留占用并提供管理入口，不能静默挤掉活跃载荷。

## 10 界面和系统入口

会话输入区增加附件按钮和附件条，每项显示缩略图/类型、名称、准备进度、转换提示、错误和移除操作。主题采用可展开字段；发送按钮在准备完成、有效收件人/SIM和政策校验通过后可用。附件为空时不自动丢失用户显式选择的彩信模式；切回短信需无主题/附件并有可发送正文。

状态区不展示伪造百分比：本地导入/压缩可显示实际进度；系统传输只显示“发送中”。气泡或其下方显示发送SIM、失败原因/重试入口、待确认说明。回调结果不是仅一次性 Snackbar，重新打开会话仍能看到。

编辑预览复用 Coil/Media3 的私有本地内容能力，但用 draft attachment身份；正式发送记录经 Provider/镜像后才使用 `MmsPartKey`。现有接收附件的打开、导出、暗色、Dynamic Color、主题气泡、多选、长按、字体缩放、IME/navigation inset和播放器生命周期不退化。

`ComposeSmsActivity` 保留名称以减少系统入口变更，分离 SENDTO 的 scheme filter与 SEND/SEND_MULTIPLE 的 MIME filter；不要把 HTTP可浏览链接纳入附件入口。ACTION_SEND、EXTRA_STREAM、ClipData和多选上限统一校验，重复 URI 不擅自展开，重复进入同一分享不自动创建多个发送命令。外部原始主题/正文先展示，用户可改；多个号码、CC/BCC、邮件地址或不能唯一解析的收件参数只进入编辑确认，提示仅支持单人，不取第一个、不拆成多次发送，也不把整个字符串当一个号码。发送必须由用户确认唯一电话号码；已有多地址入站消息的数据不受此限制改写。

通知和 `RESPOND_VIA_MESSAGE` 仍保持现有纯 SMS行为；此版不在锁屏上直接发附件。新增 MMS 发送入口不把已有多地址入站消息的首个地址当作已确认目标，也不提供群回复；无法唯一确定用户要联系的人时打开编辑页，让用户明确一个电话号码。此防误发检查不扩展群会话模型或改变接收地址数据。Manifest修改前后复核全部默认短信资格组件、权限、exported和scheme，新增内部回调无外部 intent filter。

## 11 安全、兼容性与发布

- 不上传消息给开发者或第三方处理；发送到运营商和用户确认收件人是本功能的必要外发。同步调整 README/AGENTS 对 MMS 网络用途的准确表述及 PRIVACY 中附件类型、私有草稿/发送状态的存储说明，实施完成前不提前宣称功能已上线。
- 私有文件、数据库、响应字节与日志都按敏感消息处理。日志仅阶段、错误类别、计数和内部ID；不记录号码、正文、附件名、URI、PDU字节、内容哈希或运营商响应文本。
- 外部附件预览继续离线：HTML不开脚本、不读任意本地路径、不拉远程资源，vCard/日历解析沿用有界处理；损坏/未知文件可阻断发送或作为明确的普通附件处理，不执行其内容。
- 新增数据库不迁移旧短信，也不扫描历史 OUTBOX/FAILED并自动接管。旧版本没有发送日志的 MMS只作为历史消息展示；不能从 `msg_box` 推断有本应用授权的发送任务。
- 现有 `.ptbackup` 仍是 SMS-only，不导出草稿、队列、待回调、MMS附件或 URI授权；旧备份导入不能启动发送。应用数据迁移和降级也不重建网络任务。
- 当前镜像数据库无 schema 变更即可接收新的标准Provider 行。若实现发现确需扩展持久镜像字段，应另列 Migration和备份影响，不能直接升版本并沿用破坏性兜底。
- Composer补源需同步许可索引和应用内开源声明配置；不替换已有 parser加固，不为解决R8问题全局 keep整个应用。新增 Receiver/Provider/Room/可选反射入口在完整 Release中验证。
- 发布前先完成模拟链路与真正运营商验收，再公开发送入口。可用本地构建开关分阶段验证；不引入远程feature开关、埋点或功能上线后默认自动发信。禁用入口也必须允许既有已提交请求记录回调与补写。

## 12 验证策略与验收门槛

遵循仓库“不做单元测试”约定：不新增 `app/src/test/`、`app/src/androidTest/`、测试依赖或 `test*` 任务。设计当前只做代码/文档审查、官方契约核对、差异与引用检查；以下是后续实施验收，不是已通过结果。

| 层级 | 必须覆盖 | 证据与限制 |
| --- | --- | --- |
| 静态与构建 | Kotlin编译、Lint、Manifest资格、依赖/许可、Room schema、完整Debug与混淆Release | 用项目现有Gradle入口；最终树验证，不能拿PR17通过替新功能背书 |
| 本地PDU工具 | 中文/emoji/主题、纯文本、仅主题阻断、单/多图、GIF、音视频、vCard/日历/未知文件、CID/SMIL、恰好限额与超1字节 | 合成脱敏数据，独立解码器/对照客户端核对字节和结构；Composer/parser自循环不是独立互通证据 |
| Mock UI/故障注入 | 导入取消、权限失效、流大小未知、损坏解码、低空间、切SIM、迟到准备结果、重复点击、旋转、多窗口、返回草稿、外部多号码/CC/BCC 阻断且不取首个或拆发 | 仅私有Mock/调试入口，Release不保留可外部调用的“模拟发送成功”Receiver |
| Provider/恢复 | 插入前后崩溃、每个part中断、丢sourceId恢复、null Cursor、角色变更、删除/ID复用、重复/迟到回调、回调先到、同步失败 | 同一请求不重复建消息、既存消息不损坏、系统pending无新增用户SendReq任务 |
| 调度与防重发 | Worker重复/停止/重启、进程死亡、设备重启、DISPATCHING崩溃窗口、无回调超时、手动未知重发 | 记录平台提交次数和attempt归属；不读取用户原始消息，不把零联网Mock当运营商验证 |
| 模拟MMSC端到端 | PixelText发送→服务端解析→独立Messaging接收；交换方向回归接收 | 当前任务未确认历史本地MMSC/双模拟器可用；需重新核验，旧文档中的环境状态不是现在证据 |
| 真实运营商 | Pixel真机、明确SIM/号码/计费许可，至少实际目标运营商；单/双卡、MMS APN、默认数据卡不同、Wi-Fi/数据/漫游设置、服务拒绝 | 只有真实运营商才能证明计费链路/网络适配；未经单独授权不做真实短信或彩信发送 |
| 兼容与回归 | API31下限、目标SDK平台、ARM64完整Release；原SMS、MMS下载/解析/附件导出、已有多地址入站消息完整保留、既有会话分组、搜索/归档/主题、备份恢复、PR17修复 | ARM64翻译模拟器可证明部分Release运行，不替代native ARM64真机或运营商网络 |

### 故障恢复的不变量

- 连点、页面重建、重复Worker和重复回调只产生一个已接纳请求和一次应用提交。
- 即使平台已经发出，Provider或镜像失败也只补记录，永不为了“同步成功”再发。
- UNKNOWN不会在网络恢复、重启或升级时转为自动重试；用户另发产生独立消息与重复风险提示。
- 新增发送请求始终只有一个确认的电话号码；从不取多号码中的首个、逐人拆发、静默改收件人、换SIM、丢附件或改发短信。
- 既有会话分组规则和入站多地址数据保持原语义；单人发送不引入群会话抽象，也不删减接收解析信息。
- 删除墓碑阻止迟到回调/文件任务复活消息；授权/查询失败不会被误当删除。
- 已发送消息与接收端内容可核对，损坏内容不因本地UI看起来正常就算通过。

### 分阶段交付边界

设计通过后，执行计划按可验证阶段拆分：草稿/附件/政策 → Provider 和PDU → durable提交与回调 → UI/外部入口/镜像投影 → 故障恢复与完整Release → 运营商验收。每阶段只验证受影响内容；最终一次汇总上述关键不变量与实测矩阵。此顺序不是实施授权，也不包含合并、发布或真实发送许可。

## 13 官方依据与维护

以下于2026-10-02查阅。公共API是应用契约；AOSP源码解释当前实现，不承诺每家OEM完全一致。源码引用若使用main，实施前固定所验证的commit并在临时证据中记录；新增vendor源码继续使用项目固定基线。

- [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager)：发送权限、订阅绑定、配置、发送结果和extras。
- [PackageManager.FEATURE_TELEPHONY_MESSAGING](https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_TELEPHONY_MESSAGING)：API33新增能力标记，Android12使用兼容判断。
- [Telephony.BaseMmsColumns](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns)：箱类型、秒级日期与MMS头字段；包含FAILED常量。
- [Telephony.Mms.Addr](https://developer.android.com/reference/android/provider/Telephony.Mms.Addr) 与 [Telephony.Mms.Part](https://developer.android.com/reference/android/provider/Telephony.Mms.Part)：地址/内容子结构。
- [PendingIntent.FLAG_IMMUTABLE](https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE)：不可变回调忽略发送者填入内容；发送结果extras需要有界mutable设计。
- [CarrierMessagingService.SendMmsResult](https://developer.android.com/reference/android/service/carrier/CarrierMessagingService.SendMmsResult)：成功响应PDU可以为null。
- [AOSP MmsRequest](https://android.googlesource.com/platform/packages/services/Mms/+/refs/heads/master/src/com/android/mms/service/MmsRequest.java)：HTTP运输结果、系统内部重试、完成时持久化与回调顺序。
- [AOSP SendRequest 固定旧版](https://android.googlesource.com/platform/packages/services/Mms/+/refs/tags/android-mainline-11.0.0_r13/src/com/android/mms/service/SendRequest.java)：默认应用判断、SendConf与自动持久化；该细节此次可读取的是旧基线，目标Android版本/OEM仍需实机核验。
- [AOSP MmsServiceBroker](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/MmsServiceBroker.java)：PDU URI向phone/carrier消息服务授权。
- [AOSP MmsSmsDatabaseHelper](https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/src/com/android/providers/telephony/MmsSmsDatabaseHelper.java)：OUTBOX变更产生/清理pending消息的触发器。
- [AOSP TransactionService](https://android.googlesource.com/platform/packages/apps/Mms/+/refs/heads/main/src/com/android/mms/transaction/TransactionService.java)：旧默认MMS应用消费pending队列的具体例子。
- [AOSP PduComposer 固定来源](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/telephony/common/com/google/android/mms/pdu/PduComposer.java)：通过官方镜像实际读取，与当前vendor同一固定提交；上游编码假设必须按第7节加固。
- [官方SDK Android35 PduComposer](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/com/google/android/mms/pdu/PduComposer.java) 与 [AOSP Messaging Composer](https://android.googlesource.com/platform/packages/apps/Messaging/+/master/src/com/android/messaging/mmslib/pdu/PduComposer.java)：其他官方实现对照；不因参考实现可编码就跳过互通验收。
- [WorkManager任务管理](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)：唯一任务与取消；不能把调度唯一性解释为外部网络恰好一次。
- [接收外部分享](https://developer.android.com/develop/ui/compose/sharing/receive)：SEND/SEND_MULTIPLE、MIME与用户可编辑确认界面。

仓库目前没有总设计索引；新设计直接放`docs/plans/`并在正文互链相关设计，不新增`docs/superpowers/`或临时进度索引。实现引入Composer时更新已有`docs/licenses/README.md`及开源声明构建配置。验证样例、日志、协议数据和评审过程放已忽略的`docs/.local/`或仓库外，不作为正式文档的必需依赖。
