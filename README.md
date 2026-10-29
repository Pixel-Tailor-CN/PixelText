# Pixel Text（原点尺素）

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
- **我的上报**：按月查看本人上报记录与审核详情；支持从列表或短信详情进入脱敏表单，人工确认后提交，结果不确定时可恢复并复用原请求重试。记录与本次安装关联，卸载或清除数据后将无法访问。
- **双卡双待**：识别 SIM 卡信息，在会话中显示卡名，并支持发送/回复时选择 SIM。
- **Material You**：使用 Jetpack Compose 与 Material 3，支持动态取色、暗色模式和原生 Compose 交互。
- **名称与图标切换**：默认使用「原点尺素」与紫色信笺图标，可在设置的「显示与外观」中独立选择名称和图标，预览后应用；支持经典图标和系统主题图标。名称同步至桌面与应用主界面，系统通知顶部保持「原点尺素」。

## 隐私与网络

短信解析、智能卡片生成、骚扰短信识别和设置数据都在本地完成。只有你主动确认上报时，才会将确认后的脱敏样本及必要身份、版本字段发送到 PixelText Hub；不会自动上传短信内容进行解析或分类。

应用包含 `INTERNET` 权限，用于 MMS 等必要请求、既有资源更新，以及你主动上报和查看本人记录；短信内容解析、分类和统计不依赖网络服务。安装身份、待确认请求与显式保留草稿不参与备份，详见 [隐私政策](PRIVACY.md)。

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

Release APK / AAB 构建：

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

### 签名配置

签名由 [`signing.gradle`](signing.gradle) 读取，支持以下字段：

| 字段 | 含义 |
| --- | --- |
| `SIGN_KEY_STORE_FILE` | keystore 文件路径 |
| `SIGN_KEY_STORE_PASSWORD` | keystore 密码 |
| `SIGN_KEY_ALIAS` | 签名密钥别名 |
| `SIGN_KEY_PASSWORD` | 签名密钥密码 |

每个字段优先读取仓库根目录的 `local.properties`，未配置或值为空时再读取同名环境变量。
因此，只有 `sdk.dir` 的 `local.properties` 也可以与环境变量中的签名配置同时使用；本地同步和构建无需提供正式签名材料。

解析后的 `SIGN_KEY_STORE_FILE` 为空时，Release 构建会回退到本机的 debug 签名，便于本地验证。
配置了 keystore 路径时，应同时提供其余三个有效字段；此时不会因其他字段缺失而自动回退。
正式发布必须使用完整、有效的正式签名配置，当前[发布工作流](.github/workflows/release.yml)通过环境变量注入。

使用 debug 签名的 Release 包仍保留混淆、资源压缩和正式包名 `vip.mystery0.pixel.text`，无法直接覆盖安装到正式签名版本上。
日常调试可使用包名带 `.debug` 后缀的 Debug 构建；签名不同的本地 Release 构建可在独立测试设备或模拟器中验证。

## 第三方来源与许可

MMS 解析器的 AOSP 来源、本地修改及相关依赖的许可声明见 [第三方来源与许可](docs/licenses/README.md)。

## 开发者

Pixel Text 属于 **Mystery00** 的 Pixel 工具矩阵成员。

### 单人彩信发送（验收中）

发送链路使用独立本地草稿与执行日志，经 Android `SmsManager` 和运营商网络传输，复用现有 MMS 消息展示。编辑入口已随普通构建开放；发送前仍需确认收件人、SIM、内容和资费风险。完整系统链路与目标运营商实测结果需按设备和运营商单独验收。

只接受一个明确电话号码；支持正文、主题和经本地检查的附件。静态图片可本地适配，音视频与文件只发送体积合规原件。系统接管后无法撤回；结果未知不会自动重发，手动另发会提示重复送达和费用风险。完整约束见[设计](docs/plans/2026-10-02-mms-sending-design.md)与[实施计划](docs/plans/2026-10-02-mms-sending-implementation-plan.md)。
