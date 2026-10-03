# 统一消息编辑器实施计划

> 实施方式：在当前会话按任务实现与验证。遵循项目文档结构，不新增单元测试或测试依赖；共享 Gradle 构建串行执行。本文描述交付步骤，运行日志和实际完成记录放在 `docs/.local/`，不把已开始的代码改动视为验收通过。

**目标：** 提供接近 Google Messages 的统一会话编辑体验，文字走 SMS、附件走 MMS，支持拍照和前台录音。

**架构：** 复用现有持久草稿、附件准备及两个发送协调器。统一 Compose 编辑组件处理输入与预览，ViewModel 编排自动准备和持久接纳，不重写平台传输链路。

**技术栈：** Kotlin、Compose/Material 3、Koin、既有 Room/WorkManager、Android Activity Result、MediaRecorder/MediaPlayer。

**设计：** [统一消息编辑器设计](2026-10-03-unified-message-composer-design.md)。用户已确认采集范围及首次费用确认策略；完整细化文档尚未标记为用户审阅通过。

## 全局约束

- 基线 `bda2c2a424c8f40a42ede1def942e857a7db490d`；不覆盖已提交的八项可靠性修复。
- 仅单人 SMS/MMS；不增加 RCS、群发、自动重发、远程压缩或消息上传。
- 不改变默认 MMS 发布开关；模拟器通过不等同生产运营商验收。
- 不静默删附件、换卡、丢弃输入或以 SMS 代替失败的 MMS。
- 保留草稿版本栅栏、未知结果重发确认及发送后不可撤回的边界。
- UI 使用 Compose、Material 3 和现有主题，不新增 XML layout。
- 拍照调用系统相机；录音按需申请 `RECORD_AUDIO`，不后台录音。
- 不新增 `app/src/test/`、`app/src/androidTest/` 或测试依赖，不运行单元测试任务。
- 不清空现有模拟器；不擅自提交、推送或发布。

## 重点评审场景

1. 准备期间修改正文/附件/SIM，或快速重复发送：不能提交旧内容或创建重复命令。
2. 导入部分失败、外部 URI 授权失效：必须保留错误并阻止不完整提交。
3. 拍照返回、录音中断、权限拒绝和页面重建：原草稿保留，采集资源正确释放。
4. 首次资费确认、取消确认及 UNKNOWN 重发：普通确认不能绕过重复收费风险提示。
5. 旧草稿、外部分享和含主题输入：不能因新旧入口切换而丢字段或改变目标号码。

## 文件与接口约定

以下代码路径相对于 `app/src/main/java/vip/mystery0/pixel/text/`。新增组件文件名为本轮拟定职责边界，接入时与调用方一起核对，不为匹配文档而拆分无关代码。

### 任务 1：统一提交编排与草稿生命周期

文件：修改 `viewmodel/MmsComposerViewModel.kt`、`di/AppModule.kt`；必要时调整 `data/repository/OutgoingMmsRepository.kt` 的草稿接口。

接口：`submit()` 根据当前草稿选择 SMS 或自动准备 MMS；`startNextMessage()` 在已接纳后开启下一条编辑。既有 `open`、`edit`、`importAttachments` 和 `removeAttachment` 继续负责版本和导入状态。

- [ ] 明确正文的唯一状态来源，梳理旧主题、外部分享与恢复编辑的内容映射，禁止静默丢字段。
- [ ] 接入 `SmsSendCoordinator`；MMS 从一次提交自动进入准备与接纳，不要求 UI 再点“检查”。
- [ ] 保留准备版本校验、失败输入、选卡约束和未知重发来源；接纳后仅清空对应版本。
- [ ] 验证重复点击、准备期间取消、进程恢复和下一条输入不被旧结果清除。

### 任务 2：附件采集与录音

文件：新增 `ui/message/mms/ComposerCapture.kt`；修改 `app/src/main/AndroidManifest.xml`，必要时核对现有 FileProvider 路径。

接口：采集层只交付可读取的本地 `Uri`，统一调用 ViewModel 的 `importAttachments`，不绕过原件大小和内容校验。

- [ ] 系统相机输出到私有临时文件，成功后导入，取消不改变旧草稿。
- [ ] 实现麦克风按需授权、前台录制、时长、取消和停止；音频附件支持试听。
- [ ] 处理后台/离页/重建及设备不可用，释放录音和播放资源并清理无引用临时文件。
- [ ] 核对 Manifest 默认短信资格入口，验证授权、拒绝、取消和恢复路径。

### 任务 3：统一编辑器与附件预览

文件：新增 `ui/message/mms/MessageComposer.kt`；重构 `ui/message/mms/MmsComposer.kt` 的编辑界面部分。

接口：统一组件消费 `MmsComposerViewModel.State` 并调用编辑/导入/提交命令；收件人、初始内容和提交完成由宿主提供，不直接调用传输 API。

- [ ] 实现底部圆角正文区、附件面板、紧凑 SIM 与 SMS/MMS 标识及发送按钮。
- [ ] 实现图片、视频、音频和普通文件预览、删除与就地错误提示。
- [ ] 实现首次费用确认；后续正常 MMS 直接提交，UNKNOWN 重发仍单独确认。
- [ ] 验证键盘展开、小屏幕、深浅色、Dynamic Color、既有输入区自定义和旋转恢复。

### 任务 4：入口统一与消息状态

文件：修改 `ui/screen/ConversationDetailScreen.kt`、`ComposeSmsActivity.kt`、`ui/message/mms/MmsComposer.kt`；按实际接入核对导航调用方。

- [ ] 会话页替换双轨 SMS/MMS 输入，删除旧手动准备表单入口。
- [ ] 外部分享、系统编辑、旧草稿及失败恢复使用新编辑组件，Intent 只预填不自动发送。
- [ ] 状态紧贴对应消息；重试、恢复编辑及清理操作按需出现，不永久挤占输入区。
- [ ] 验证收件人/会话对应、草稿保留、附件全部删除后的协议切换及原有消息阅读功能。

### 任务 5：构建、视觉检查与实际收发

- [ ] 运行 `./gradlew.bat :app:assembleDebug :app:lintDebug '-Ppixeltext.enableMmsSending=true' --no-daemon`，确认成功；保持源码默认开关不变。
- [ ] 在现有 Pixel_6a / Pixel_10a 上检查文字、单附件、多附件、录音和键盘状态，保存深浅色截图。
- [ ] 实际发送 SMS、图片 MMS 及可用的其他附件类型，核对接收端内容；不能把选择器或预览成功当作传输成功。
- [ ] 检查资费确认、超限、导入失败、取消、UNKNOWN 重发与迟到结果展示。
- [ ] 运行 `git diff --check`，复核权限、文件授权、临时文件回收和不自动重发边界。
- [ ] 交付时逐项区分实测、诊断注入、代码复核及未验证范围；未获得真机/运营商证据的项目不得标为通过。

## 文档维护

正式设计约束集中在[设计文档](2026-10-03-unified-message-composer-design.md)，本文件保持任务和验收契约。工作进度、会话交接、截图、临时脚本与日志只写入 `docs/.local/`，正式文档不引用这些文件作为必需资料。
