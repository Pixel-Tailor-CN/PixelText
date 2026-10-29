# 我的上报实施计划

> 按当前用户授权与根目录 AGENTS.md 执行，不新增或运行单元测试，不自动提交。设计已经确认，不重复申请设计审批。

**目标**：替换旧上报入口，交付按月记录列表、审核详情、可恢复的幂等提交及原会话快捷上报闭环。

**架构**：沿用 Retrofit/Moshi、Koin、Compose、Coroutines/StateFlow。网络与安装身份、待确认请求持久化归 Data；强类型模型归 Domain；ViewModel 管理请求代次与交互状态。身份和已确认的提交负载写入 `noBackupFilesDir`，查询记录仅保留页面内存，避免移除后从磁盘缓存回显正文。

**设计依据**：[已确认界面方案](2026-10-29-my-reports-ui-design.md)。接口已核对本地服务端 `docs/design/sample-reports-api.md` 与 `backend/internal/sample/reports*.go`；以下计划列出实施必需的契约，不修改服务端。

## 实施进度（2026-10-29）

产品代码、必要接线和隐私说明已完成，`compileDebugKotlin` 与 `lintDebug` 均通过。已勾选的功能项表示实现完成，不代表运行时设备验收；已完成核心设备联调与独立源码复审，完整验证边界见文末。未运行单元测试，未提交、推送或发布。

## 全局边界

- 列表与详情查询 JSON 携带 `androidId`、`installationId`；查询 Android ID 不可用可为空，提交要求16位十六进制 Android ID。
- 安装身份为安全随机32字节，经 Base64URL 无填充编码43字符，原子保存，不能记录日志、放进URL或纳入系统和应用备份。文件损坏时显示可恢复错误，不静默生成新身份丢失归属。
- v2提交包含固定 UUID `clientRequestId`、身份、用户确认内容、发送方、分类、三个版本。重试包括版本字段都保持原负载。
- 所有正文样例为合成脱敏数据；不向生产服务发测试样本，不记录请求体、身份、发送方或正文。保留现有人工确认与脱敏助手，不自动上传。
- 不新增依赖、不修改系统短信权限与默认角色资格，不做会话分组。
- 错误面向用户给中文提示，协程取消继续抛出，日志仅英文非敏感短语。

## 任务一：v2契约、身份与可恢复提交

**文件**（Kotlin路径以 `app/src/main/java/vip/mystery0/pixel/text/` 为根）：
- 新增 `domain/hub/SampleReportModels.kt`：摘要、详情、游标页、提交结果、公共状态与错误模型。
- 新增 `data/source/SampleReportDtos.kt`：Moshi请求与响应DTO及映射。
- 新增 `data/repository/SampleReportLocalStore.kt`：安装身份、单个待确认请求及可选未提交草稿的独立生命周期。
- 修改 `data/source/PixelTextHubClient.kt`、`data/repository/SampleSubmissionRepository.kt`、`domain/hub/HubModels.kt`、`di/AppModule.kt`。

- [x] 模型保留字符串记录ID、不把状态或类别未知值视为解析失败；RFC3339日期可解析为Instant，空审核时间可空。摘要字段为 id/source/contentPreview/submittedCategory/reviewedCategory/status/reasonCode/reasonText/reviewMethod/submittedAt/statusUpdatedAt/reviewedAt；详情另含 submittedContent/submittedSender。
- [x] Client 提供 `submitReport(request): SampleReportSubmission`、`listReports(identity, cursor, limit=20): SampleReportPage`、`getReport(identity, reportId): SampleReportDetail`，分别调用三个 v2 POST。保留其他资源接口不变，移除旧提交调用。响应分别读取 `{report,created}`、`{items,nextCursor}`、`{report}`。
- [x] 解析稳定错误码与 Retry-After；400/404/409/413/429/503按契约映射，错误体不直接打印。分页游标原样传回，无效游标显式提示重新刷新，不暗中跳第一页。
- [x] LocalStore使用互斥锁及AtomicFile保证并发身份初始化与请求写入；网络发出前先持久化完整确认负载，落盘失败不发送。待确认请求与普通编辑草稿分开，成功取得确定report后清除待确认负载。
- [x] Repository执行 Unicode字符数和UTF-8字节长度校验：正文trim后至少6个Unicode字符、最多64KiB；sender最多256字符、版本各128字符，分类使用既有9项枚举。
- [x] 同一次待确认提交只允许恢复与复用原请求，不因旋转、退页面、更新版本或重启换UUID；同一时刻只发一个请求。若用户另起新表单时有旧请求，先恢复旧请求，不能覆盖它。
- [x] 明确失败且服务端未接受的校验错误可返回编辑；网络/超时/取消/不确定服务端错误保留请求；409保留请求并提示冲突，不自动另起编号。身份损坏、请求文件损坏不悄悄抹除。
- [x] 重试先查询核对，但不依据摘要相似自动判成功；不能证明成功时，用户明确重试复用同一UUID和完整负载取得确定结果。退出后列表展示独立「有一条提交尚未确认」恢复入口，不当作审核记录。

**验证**：编译；独立审查身份排除备份、并发写入、进程终止窗口和版本变化。设备用合成数据验证首次提交、重复点击、断网、恢复与重试；无生产上报。

## 任务二：列表与详情

**文件**：新增 `viewmodel/SampleReportListViewModel.kt`、`viewmodel/SampleReportDetailViewModel.kt`、`ui/screen/SampleReportListScreen.kt`、`ui/screen/SampleReportDetailScreen.kt`、`ui/screen/SampleReportComponents.kt`，修改 `di/AppModule.kt`。

- [x] ViewModel通过StateFlow提供列表、分页、刷新、错误、详情状态；以请求代次取消旧请求，刷新期间拒绝旧分页结果污染，ID去重但保留服务端次序。
- [x] 列表按设备时区把submittedAt映射到年月标题，跨分页同月不重复标题；年月使用绝对年份，日期格式跟随现有项目习惯。未知日期保守显示，不把错误记录归入本月。
- [x] 实现已选平面布局：返回/我的上报/上报标题栏，月份标题，分类与图标+文字状态、两行摘要、日期、导航箭头、细分隔线。行内文字不与状态挤占；大字号可换行。
- [x] 下拉刷新、触底分页、列表空态、首次失败、追加失败重试、限流等待，返回保留滚动位置。记录说明放低强调入口，列表无总数、无状态筛选、无虚构审核时间线。
- [x] 详情独立加载当前服务端结果，按设计呈现状态、原因、正文、发件方、分类差异、时间、审核方式和编号。removed立即清掉已显示正文与发送方，404不回显旧详情。未知状态/分类有回退文案。
- [x] 审核通过附说明「审核通过不代表已用于训练或发布规则」，不夸大服务端状态。空发件方、legacy来源及无审核时间正确处理。

**验证**：编译及设备检查长中文、跨月追加、快速刷新、返回位置、暗色、大字体、未知状态、removed和404。

## 任务三：表单与导航闭环

**文件**：修改 `viewmodel/SampleSubmissionViewModel.kt`、`ui/screen/SampleSubmissionScreen.kt`、`ui/AppNavigation.kt`、`ui/screen/SettingsScreen.kt`；必要的原会话成功提示接线可修改 `ui/screen/ConversationDetailScreen.kt`，不改短信业务。

- [x] 设置旧入口文字与回调改为「我的上报」，新增列表路由 `sample_reports` 和详情路由 `sample_report/{reportId}`。编号验证后用于导航，身份只放请求体；正文不拼URL。
- [x] 现有 `sample_submission` 作为新建表单继续复用，短信详情传入草稿流程保留。表单StateFlow统一提供发送状态、错误与一次性成功事件。
- [x] 正文优先的平面表单、辅助脱敏、类别、可选发送方与人工确认保留；确认后的相关内容变化应撤销确认。未确认原始短信不写入持久化待提交请求。
- [x] 输入超限原位提示，不截断；提交中防重复，取消异常不伪报普通失败。结果不确定锁定本次已发负载，提供查看记录/重试本次提交/保留并返回，恢复时不覆盖原请求。
- [x] 普通编辑草稿有修改时返回弹出保留或放弃；明确保留才将用户当前编辑内容保存本机不备份私有目录，文案解释只保留本机，未保留的原始预填不自动落盘。
- [x] 成功事件携带reportId，按原入口pop回列表或会话并显示「样本已上报」+「查看」。Snackbar由返回目标或导航共同宿主持有，避免表单退出后提示消失；点击查看入详情，返回仍回原入口。列表刷新而不留成功表单在返回栈。
- [x] 从超时表单进入记录页再返回，不丢待确认请求；查看动作不自动判定本次请求成功。

**验证**：列表发起与短信发起两条路径，系统返回/顶部返回一致，失败保留、取消脱敏、旋转与进程重启、成功事件不重复消费。

## 任务四：文档、审核与交付

**文件**：更新 `PRIVACY.md` 与必要的 README 功能说明，计划勾选只反映真实完成。

- [x] 隐私说明新增安装身份、请求UUID、本人记录查询与历史认领、不参与备份、重装后不可访问、主动确认提交边界；确认应用手动备份白名单不包含新增存储。
- [x] 顺序运行 `./gradlew.bat :app:compileDebugKotlin :app:lintDebug`；需要安装时运行 `:app:assembleDebug`。不运行任何单元测试任务。
- [x] 使用已有设备与可用服务进行目标页面验证；真实网络联调仅使用合成样本与本地/开发服务。若服务不可用，记录未验项，不能用编译代替接口验证，也不擅自创建大型验证基础设施。
- [x] 独立审查固定改动快照，重点关注身份保护、幂等状态、分页竞态、取消处理、移除内容和返回栈。修复后复核相关项，不重复无关构建。
- [x] 交付改动与验证结果，说明未验环境；不自动提交、推送、发布或修改后端。

## 审查重点

1. 同一请求超时后跨进程/版本重试，字段和UUID必须全部固定，不能重复计数。
2. 刷新与旧分页交错，保持新列表顺序，不能漏项或追加旧游标结果。
3. 详情变为removed/404，不继续回显旧正文；未知状态安全回退。
4. 从短信详情上报后回原会话，查看审核再返回仍保持来源，提示不丢失。
5. 安装身份与待确认/显式保留草稿不进入系统、迁移或手动备份；损坏不能静默换身份。

## 最终实现与验证范围

已实现替换入口、按月本人列表、审核详情、固定负载幂等请求、草稿与待确认恢复。独立源码复审通过，返回时丢失分页、提交落盘前退出、存储恢复按钮三个审查问题已修复。最终 Kotlin 编译、Lint 和 Debug APK 构建通过；按项目约定没有新增或运行单元测试。

网关开放 v2 路由后，在 Android 模拟器完成单条合成样本的实际提交、列表、详情、审核状态回传与成功查看返回；断网保留请求，结束进程并覆盖安装后请求文件摘要保持一致，恢复重试成功后清除待确认文件。另验证了旧草稿与当前短信选择、普通草稿跨进程恢复、脱敏助手取消、明暗主题和1.3倍字号。设备测试只使用合成内容。

尚未实测大批量跨月分页、磁盘故障注入、移除/限流状态变化、已成功UUID重复提交、短信来源成功提交、真实备份恢复及TalkBack；相关控制流已做源码审查，不能等同于这些场景的运行验证。没有提交、推送或发布代码。
