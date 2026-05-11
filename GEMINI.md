# Pixel Text (原点短信) - AI 开发上下文指南

这份文档用于帮助 Gemini 理解 **Pixel Text** 项目的背景、架构和开发规范。在进行任何代码编写或架构设计时，请严格遵循本文档中的规则。

## 1. 项目概览

- **包名**: `com.mystery00.pixeltext`
- **目标平台**: Android 12+ (专为 Google Pixel 系列设计)
- **核心卖点**: 纯净无广告、支持网络功能及彩信(MMS)处理、本地化智能文本解析（验证码、12306等）、原生 Material You
  审美。
- **项目定位**: 补足国内 Pixel 用户的本地化短信体验短板，作为默认短信应用运行。

## 2. 核心技术栈与架构

- **编程语言**: Kotlin
- **UI 框架**: Jetpack Compose (Material 3)
- **依赖注入**: Koin
- **应用架构**: MVVM + Clean Architecture
- **异步处理**: Kotlin Coroutines + Flow
- **数据源**: `ContentProvider` (读取 `content://sms` 等 Telephony 数据库)

## 3. 给 AI 的绝对开发准则（红线）

1. **网络与彩信(MMS)**: 允许在 `AndroidManifest.xml` 中添加 `android.permission.INTERNET` 权限，以支持彩信(MMS)的下载、解析和展示以及相关网络请求。
2. **纯 Compose UI**: 所有 UI 必须使用 Jetpack Compose 实现，**禁止**生成 XML 布局文件。使用
   `dynamicColorScheme` 适配系统主题色。
3. **职责分离**:
    - **UI 层**: 只负责渲染和用户交互。
    - **Domain 层 (UseCase)**: 存放核心的解析逻辑（如验证码提取、12306 结构化）。
    - **Data 层 (Repository)**: 封装 `ContentResolver`，负责与系统短信数据库的 CRUD 交互。
4. **不涉足 RCS**: 明确本项目只做传统的 SMS/MMS 增强，不涉及 RCS 逻辑的开发。

## 4. 当前阶段开发重心：基础短信应用与功能完善

当前项目正从原型阶段进入核心功能完善阶段。

### 核心短信功能 (基础接管)

在 UI 验证完毕后，实现以下基本短信管理功能：

- 声明必须的四大组件（`SmsReceiver`, `MmsReceiver`, `HeadlessSmsSendService`, `ComposeSmsActivity`
  ）以获取默认短信应用权限。
- 基础的短信 CRUD 操作：读取会话、发送短信、**标记已读**、**删除短信**、**标记为骚扰**。
- *关于彩信 (MMS)*：实现彩信的接收、下载、解析和展示。

### 细节与体验优化

- 适配 `AnimatedVisibility` 等原生动效。
- 暗色模式与 Material You 动态取色优化。