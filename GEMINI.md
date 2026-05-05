# Pixel Text (原点短信) - AI 开发上下文指南

这份文档用于帮助 Gemini 理解 **Pixel Text** 项目的背景、架构和开发规范。在进行任何代码编写或架构设计时，请严格遵循本文档中的规则。

## 1. 项目概览

- **包名**: `com.mystery00.pixeltext`
- **目标平台**: Android 12+ (专为 Google Pixel 系列设计)
- **核心卖点**: 纯净无广告、**绝对零网络权限**、本地化智能文本解析（验证码、12306等）、原生 Material You
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

1. **零网络权限**: **绝对禁止**在 `AndroidManifest.xml` 中添加 `android.permission.INTERNET`
   。所有的业务逻辑、正则匹配、解析提取都必须在本地完成。（注：这也意味着本作天然不支持且不处理需要网络下载的彩信
   MMS 具体内容）。
2. **纯 Compose UI**: 所有 UI 必须使用 Jetpack Compose 实现，**禁止**生成 XML 布局文件。使用
   `dynamicColorScheme` 适配系统主题色。
3. **职责分离**:
    - **UI 层**: 只负责渲染和用户交互。
    - **Domain 层 (UseCase)**: 存放核心的解析逻辑（如验证码提取、12306 结构化）。
    - **Data 层 (Repository)**: 封装 `ContentResolver`，负责与系统短信数据库的 CRUD 交互。
4. **不涉足 RCS**: 明确本项目只做传统的 SMS/MMS 增强，不涉及 RCS 逻辑的开发。

## 4. 当前阶段开发重心：原型验证与 UI 渲染

当前项目处于**原型验证阶段**，优先专注于“短信正则匹配自动解析和渲染”的效果，随后再接入真实的系统短信流。

### 原型验证阶段

- 编写独立的纯函数正则解析引擎（针对 12306 行程、验证码等）。
- 构造 Mock 数据，使用 Jetpack Compose 渲染解析后的 Material 3 智能卡片，用于确认最终的视觉效果。

### 核心短信功能 (基础接管)

在 UI 验证完毕后，实现以下基本短信管理功能：

- 声明必须的四大组件（`SmsReceiver`, `MmsReceiver`, `HeadlessSmsSendService`, `ComposeSmsActivity`
  ）以获取默认短信应用权限。
- 基础的短信 CRUD 操作：读取会话、发送短信、**标记已读**、**删除短信**、**标记为骚扰**。
- *关于彩信 (MMS)*：为了成为默认短信应用，必须在 Manifest 中声明 `MmsReceiver`
  ，但无需实现实际的彩信解析和展示（当前国内环境极少使用，且应用无网络权限无法下载彩信）。

### 细节与体验优化

- 适配 `AnimatedVisibility` 等原生动效。
- 暗色模式与 Material You 动态取色优化。
