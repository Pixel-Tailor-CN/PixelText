# Pixel Text（原点短信）

Pixel Text 是一款面向国内 Pixel 用户的 Android SMS/MMS 应用，目标是在保持原生 Android 审美和隐私优先的前提下，补足
Pixel 系列在国内默认短信体验上的本地化短板。

应用支持作为系统默认短信应用运行，接管基础短信和彩信收发，并通过本地规则与端侧模型把验证码、票务、银行动账、快递等服务短信转化为
Material You 卡片。

## 核心特性

- **默认短信应用支持**：声明并实现 SMS/MMS 投递接收、快速回复、外部发送入口和通知操作所需的 Android 组件。
- **短信和彩信收发**：支持短信读取、发送、会话详情展示、MMS 接收、下载、解析和图片展示。
- **本地智能卡片**：基于内置 `rules.json` 在本地解析验证码、12306/汇联易票务、银行动账、快递通知等短信。
- **验证码快捷体验**：验证码卡片支持一键复制，通知中可显示验证码复制操作。
- **骚扰短信识别**：使用内置 TensorFlow Lite 模型进行端侧识别，支持新短信识别、历史短信扫描、骚扰会话列表和相关提醒设置。
- **会话管理**：支持会话列表本地缓存、下拉刷新、搜索筛选、归档、删除、标记已读和全部已读。
- **消息操作**：会话详情支持多选、删除、复制、分享、转发、手动标记骚扰/非骚扰，以及原文双指缩放。
- **双卡双待**：识别 SIM 卡信息，在会话中显示卡名，并支持发送/回复时选择 SIM。
- **Material You**：使用 Jetpack Compose 与 Material 3，支持动态取色、暗色模式和原生 Compose 交互。

## 隐私与网络

短信解析、智能卡片生成、骚扰短信识别和设置数据都在本地完成，不会把短信内容上传到远程服务。

应用包含 `INTERNET` 权限，当前用于 MMS 下载等必要网络请求；短信内容解析、分类和统计不依赖网络服务。

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose、Material 3、Navigation Compose
- **架构**：MVVM + Clean Architecture
- **异步**：Coroutines、Flow、WorkManager
- **依赖注入**：Koin
- **本地存储**：Room、SharedPreferences
- **系统数据源**：Android Telephony Provider、ContentResolver
- **端侧模型**：TensorFlow Lite

## 项目信息

- **包名 / namespace**：`vip.mystery0.pixel.text`
- **最低系统版本**：Android 12（API 31）
- **目标 / 编译 SDK**：由 `gradle/libs.versions.toml` 管理
- **版本号**：由 `gradle/libs.versions.toml` 与 Git 提交信息生成

## 主要目录

```text
app/src/main/java/vip/mystery0/pixel/text/
├── data/          # Room 数据库、Repository 实现、系统数据源
├── domain/        # 领域模型、解析器、仓库接口、骚扰识别接口
├── di/            # Koin 依赖注入
├── mms/           # MMS WAP Push 解析与下载回调
├── notification/  # 短信通知与历史识别通知
├── receiver/      # SMS/MMS/通知操作 Receiver
├── service/       # 默认短信应用快速回复 Service
├── ui/            # Compose UI、卡片、搜索、设置、会话页面
├── util/          # 工具类
└── worker/        # 后台骚扰识别任务
```

## 构建

在仓库根目录使用 Gradle Wrapper：

```bash
./gradlew assembleDebug
```

常用检查：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

Release 构建需要本地签名配置：

```bash
./gradlew assembleRelease
```

## 开发者

Pixel Text 属于 **Mystery00** 的 Pixel 工具矩阵成员。
