# MMS 完整接收执行文档

## 当前实现核对（2026-11-07）

接收、内容展示与附件操作已接入；本文的模拟器环境是 2026-09-07 历史记录，不代表当前仍在线，也不据此补写未实测的接收矩阵。

- `MmsReceiver` 将 WAP Push 交给 `MmsIncomingPduHandler.enqueue()`；通知与报告分流，下载由 `MmsDownloadCoordinator`、`RetrieveConfParser`、`MmsProviderWriter` 完成，接收确认由 `MmsReceptionResponseSender` 持久化调度。`autoDownloadMms=false` 为默认值。
- 当前已有 Coil/GIF、Media3、jsoup、ez-vcard 依赖（版本见 `gradle/libs.versions.toml`）；内容入口是 `MmsContentRepositoryImpl`、`MmsContent`、`MmsPartScreen`，不是早期只有 imageUris 的适配器。
- HTML 使用 `MmsHtmlParser` 清洗及 `MmsHtmlResourceHandler` 自定义拦截供给本地资源，`MmsHtmlViewer` 禁用网络/JS/任意 file/content 访问；未直接采用 WebViewAssetLoader 类。搜索仅使用独立 searchBody（纯文本、HTML 可读文字和主题），不会把名片、日历或 SMIL 全文混入搜索。
- 用户主动单人 MMS 编辑发送已经由后续 `MmsComposerViewModel`、`OutgoingMmsRepository` 和 `mms/outgoing/` 实现；“不实现发送”仅限定本接收方案的原始范围，不代表当前产品缺少发送能力。
- `.github/runtime` 当前仅保留 Release 混淆后 vCard 与 ARM64 TensorFlow Lite 初始化检查，不能当作完整 MMS 接收/播放/协议确认回归证据。

源码路径相对 `app/src/main/java/vip/mystery0/pixel/text/`，真机及运营商未验项仍以验收记录为准。

## 目标与约束

落实[接收设计](2026-09-07-mms-reception-design.md)：实现通知接收、下载恢复、完整内容保存、分类展示、播放与附件导出。保留 Telephony 权威数据源及现有本地镜像架构；本阶段不实现用户主动 MMS 编辑与发送。

使用 Kotlin、Compose、Material 3、Koin、Room、Coroutines/Flow。最低 SDK 31、JVM 21，依赖版本由版本目录统一管理。图片采用 Coil，音视频采用 Media3；HTML 采用 jsoup 清洗与系统 WebView 离线展示。

不新增单元测试、仪器测试或测试依赖，不运行单元测试任务。验证不清空用户消息，不重配置现有 MMSC/APN，不修改无关构建选项。过程记录及临时验证产物放在已忽略的本地目录，不提交 Git。

## 实施顺序

### 1. 接收输入与构建基线

确认默认短信角色、设备版本、源 SIM 和 MMSC 连接。使用标准 WSP 二进制 multipart 合成输入；核对载荷、部件类型及原始字节，避免将文本 MIME boundary 当作有效 WSP 输入。

### 2. 完整内容模型与有界读取

在 `domain/model/mms/` 定义部件标识、内容状态及演示模型；通过 `MmsContentRepository` 订阅派生内容。`MmsPartReader` 统一内联文本与流来源，并对字符集、字节预算、缺失附件与取消进行处理。依赖在 `di/AppModule.kt` 注册。

### 3. multipart 与 SMIL 语义

解析 mixed、related 和 alternative，保留容器及全部原件。正文选择不导致附件丢失；畸形嵌套内容保留有效兄弟项。SMIL 禁用实体和外部引用，设置深度、页数与大小限制，失败时回退叶子附件。

### 4. 附件访问与导出

`MmsAttachmentExporter` 提供系统保存、临时分享与外部打开。FileProvider 只授权明确附件；处理空名称、重复名称、超长 UTF-8 名称和缺失流。历史文本转码副本明确显示来源，不冒充原始字节。

### 5. 图片和媒体

在 `ui/message/mms/` 提供本地图片、动图、音频和视频组件。共享播放控制器协调播放接管、暂停、返回与页面生命周期。错误状态保留原件操作入口。

### 6. 离线 HTML

清洗 HTML 并生成有界文本摘要，独立页面展示完整内容。WebView 禁用 JavaScript、文件与任意 content 访问、网络加载；CID 和 Content-Location 仅解析当前消息的资源。外部链接经明确用户操作和确认后交给系统。

### 7. 名片与日历

使用 ez-vcard 解析名片，使用本地有界解析器处理日历。支持多联系人、多事件及可读字段；系统导入由用户确认。复杂重复规则、未知时区和不支持字段提示限制并保留原件。

### 8. 统一展示与索引

会话和独立详情共用 `MmsContent`。仅有附件时不显示“不支持的消息”；SMIL 与容器不混入正文。可读摘要服务于会话和通知；搜索另用只含纯文本/HTML 可读文字的 searchBody 及主题；通过 revision 与索引版本失效派生缓存，数据库新增字段采用显式迁移。

### 9. 接收状态机与协议响应

广播仅负责原事件落盘和后台调度。协调器维护稳定事务身份、原始过期时间、源 SIM、下载认领、删除与恢复语义。Provider 成功保存后发送 NotifyResp 或 Acknowledge，确认使用独立持久队列重试。送达和已读报告单独关联，不生成普通消息占位。

### 10. 集成验证

在最终集成分支执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:lintDebug :app:assembleDebug --console=plain
```

通过系统短信应用与 PixelText 互发合成消息，覆盖纯附件、图文、动图、音视频、HTML 本地资源、vCard、日历、未知文件、嵌套 multipart 和 SMIL。核对 Provider、镜像、展示与导出字节，不能只依据服务端成功状态。

检查自动/手动下载、重复通知、失败重试、进程终止恢复、下载中删除和协议确认。验证保存、分享、导入、播放、返回、暗色与字体缩放。截图、日志、状态快照及抓包仅留本地。

模拟器之外另行验证 Android 12、真实运营商、双实体 SIM、物理媒体输出及 Release 运行。没有实际验证的项目不能标记为通过。

## 维护资料

- [编码与验证规范](../development/coding-and-validation.md)
- [第三方来源与许可索引](../licenses/README.md)
