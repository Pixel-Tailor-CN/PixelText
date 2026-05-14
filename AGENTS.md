# AGENTS.md

本文件是在此仓库中工作的 AI 编码 Agent 的统一项目指南。开始任何代码编写、架构设计、规则生成或文档修改前，都应优先遵循本文件；如与旧的
Agent 专属文档存在冲突，以本文件和当前工程配置为准。

## 项目概览

Pixel Text（原点短信）是一款面向国内 Pixel 用户的 Android SMS/MMS 应用，目标是补足 Pixel
系列在国内默认短信体验上的本地化短板。应用强调原生 Android 审美、Material
You、无广告、本地智能解析、默认短信应用支持、双卡双待，以及隐私优先的端侧处理。

核心目标：

- 作为系统默认短信应用运行，接管基础 SMS/MMS 收发与管理。
- 通过本地智能解析，将验证码、12306、银行动账、快递、出行等服务短信转化为 Material You 卡片。
- 尽量保持短信解析和分类在本地完成，不为解析或统计引入网络依赖。
- 支持 MMS 下载、解析、展示，以及必要的相关网络请求。
- 支持双卡识别和发送 / 回复时的 SIM 选择。

项目基础信息：

- 根项目：`PixelText`
- Android 模块：`:app`
- 包名 / namespace：`vip.mystery0.pixel.text`
- 目标平台：Android 12+
- 最低 SDK：31
- Target / Compile SDK：由 `gradle/libs.versions.toml` 管理
- JVM target：21
- 开发者：Mystery00，Pixel 工具矩阵成员

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3 与 Dynamic Color
- MVVM + Clean Architecture
- Coroutines 与 Flow
- Koin 依赖注入
- DataStore Preferences
- Android Telephony / ContentProvider 作为 SMS/MMS 数据源
- TensorFlow Lite 垃圾短信分类
- WorkManager 后台任务

## 仓库结构

- `app/src/main/java/vip/mystery0/pixel/text/`：应用代码
- `app/src/main/assets/rules.json`：短信解析规则
- `app/src/main/assets/spam_classifier.tflite`：垃圾短信分类模型
- `docs/rule_generation_guide.md`：解析规则生成指南
- `samples/`：原始短信样本，按敏感数据处理
- `samples-desensitized/`：脱敏样本，规则开发优先使用
- `gradle/libs.versions.toml`：依赖、应用版本和 SDK 版本
- `README.md`：项目介绍

主要包结构：

```text
app/src/main/java/vip/mystery0/pixel/text/
├── data/
│   ├── db/                  # 本地数据库相关代码
│   └── repository/          # 数据层实现与系统数据访问
├── domain/
│   ├── model/               # 领域模型
│   ├── parser/              # 本地短信解析引擎
│   ├── repository/          # 仓库接口
│   └── spam/                # 垃圾短信分类领域接口
├── di/                      # Koin 依赖注入模块
├── mms/                     # MMS 解析与下载相关逻辑
├── notification/            # 短信通知相关逻辑
├── receiver/                # SMS/MMS/通知操作 Receiver
├── service/                 # 默认短信应用相关 Service
├── util/                    # 通用工具
├── worker/                  # 后台任务
└── ui/
    ├── message/             # 消息相关 UI
    │   ├── cards/           # 各类卡片组件
    │   ├── factory/         # 卡片工厂
    │   └── search/          # 搜索 UI
    ├── mock/                # Mock 数据界面
    └── theme/               # Material 3 主题
```

## 开发准则

- UI 必须使用 Jetpack Compose，不要新增 XML layout 文件。
- 优先沿用现有架构、命名和包边界，再考虑新增抽象。
- UI 层只负责渲染状态与处理用户交互。
- Domain 层承载核心业务逻辑，例如验证码提取、12306 结构化、消息解析和分类决策。
- Data 层封装 `ContentResolver`、Telephony 数据库 CRUD、本地数据源和系统 API 访问。
- ViewModel 通过 `StateFlow` 暴露状态，异步任务使用 `viewModelScope.launch`。
- 依赖注入在 `di/AppModule.kt` 中通过 Koin 配置。
- 使用 Material 3 组件和 `MaterialTheme.colorScheme`，保留 Dynamic Color 行为。
- 适配暗色模式、动态取色和必要的原生动效，例如 `AnimatedVisibility`。
- 不开发 RCS 逻辑。本项目只面向传统 SMS/MMS 增强。
- 智能短信解析应保持本地化，不要引入网络解析、远程分类、埋点分析或统计上传。
- `INTERNET` 权限仅用于 MMS 下载、解析、展示及其必要网络请求，不应用于短信内容解析或分析。
- 短信样本按敏感信息处理。开发、规则验证和文档示例优先使用 `samples-desensitized/`。

## 命名与代码风格

- 使用 Kotlin 惯用写法：data class、sealed 类型、extension function 等。
- Compose 组件使用 `@Composable`，并保持函数足够小，便于阅读和推断 UI 行为。
- UI 组件命名：`XxxScreen.kt`、`XxxCard.kt`。
- ViewModel 命名：`XxxViewModel.kt`。
- Repository 命名：接口为 `XxxRepository.kt`，实现为 `XxxRepositoryImpl.kt`。
- Model 命名：`XxxModel.kt`。
- 除非现有动态卡片 details 模式确实需要 Map，否则优先使用清晰的模型字段。
- 避免无关格式化改动。
- 不要提交生成的构建产物或本地 IDE / cache 文件。

## Material 3 与 UI

- 优先使用 `MaterialTheme.colorScheme` 中的颜色。
- 优先使用 `Card`、`Surface`、`Button` 等 Material 3 组件。
- 遵循 Dynamic Color 规范，保持与系统主题色一致。
- 消息卡片应服务于快速识别和操作，不要把业务解析逻辑写进 UI 组件。
- `ui/mock/MockMessageScreen.kt` 可用于快速验证卡片渲染效果。

## 依赖注入

使用 Koin 管理依赖。模块定义位于 `di/AppModule.kt`，新增 Repository、Parser、Classifier、ViewModel
或系统服务封装时，应优先接入现有 Koin 模块。

典型结构参考：

```kotlin
val appModule = module {
    single { MessageParser(androidContext()) }
    single<MessageRepository> { MessageRepositoryImpl(androidContext(), get()) }
    viewModel { MessageViewModel(get()) }
}
```

## 消息解析引擎

`MessageParser` 位于 `domain/parser/MessageParser.kt`，使用多级漏斗过滤（Cascade Pipeline）降低正则匹配成本。

过滤层级：

- L1：`sender_equals`，发件人号码精确匹配。
- L2：`signature_equals`，短信签名精确匹配，例如 `招商银行`。
- L3：`keywords`，关键词数组，例如 `["验证码", "code"]`。
- L4：兜底规则。新增规则时不要依赖兜底路径，除非确有明确理由。

规则配置文件：

- `app/src/main/assets/rules.json`

编辑解析规则前必须阅读：

- `docs/rule_generation_guide.md`

规则 JSON 基本结构：

```json
{
  "id": "业务名_特定场景_编号",
  "target_card": "目标卡片类型",
  "priority": 100,
  "fast_fail": {
    "signature_equals": "短信签名(选填)",
    "sender_equals": "发件人号码(选填)",
    "keywords": [
      "关键词1",
      "关键词2"
    ]
  },
  "conditions": {
    "content_regex": "带有Java命名捕获组的正则表达式"
  }
}
```

规则要求：

- 每条生产规则都必须包含 `fast_fail`。
- 优先使用 `signature_equals` 或 `sender_equals`；没有稳定签名或号码时使用 `keywords`。
- 使用 Java 命名捕获组：`(?<name>pattern)`。
- JSON 中正确转义正则反斜杠，例如 `\d` 应写成 `\\d`。
- 捕获组名称必须匹配目标卡片模型的字段要求。
- 具体规则的优先级应高于宽泛兜底规则。
- 如果规则来自真实短信文本，需要新增或更新脱敏样本。
- 若遇到相同 `id`，替换旧规则；若 `signature_equals` 和 `keywords` 高度相似，优先合并或优化。

支持的卡片目标：

| 卡片目标               | 组件类                       | 用途        |
|--------------------|---------------------------|-----------|
| `TrainTicket`      | `TrainTicketCard.kt`      | 火车票 / 高铁票 |
| `BankTransaction`  | `BankTransactionCard.kt`  | 银行动账      |
| `PhoneRecharge`    | `PhoneRechargeCard.kt`    | 手机充值 / 交费 |
| `VerificationCode` | `VerificationCodeCard.kt` | 验证码       |
| `Flight`           | `FlightCard.kt`           | 航班信息      |
| `ExpressDelivery`  | `ExpressDeliveryCard.kt`  | 快递到达通知    |
| `NormalMessage`    | `NormalMessageCard.kt`    | 普通消息      |
| `OriginalText`     | `OriginalTextCard.kt`     | 原始文本      |
| `SpamMessage`      | `SpamMessageCard.kt`      | 垃圾短信      |
| `MmsImage`         | `MmsImageCard.kt`         | MMS 图片    |

## 默认短信应用与 Android 组件

默认短信应用支持依赖以下组件和 intent 行为：

接收器：

- `receiver/SmsReceiver.kt`：接收 `SMS_DELIVER_ACTION`。
- `receiver/MmsReceiver.kt`：接收 `WAP_PUSH_DELIVER_ACTION`。
- `receiver/NotificationActionReceiver.kt`：处理通知栏操作，例如标记已读、回复、删除。

服务：

- `service/HeadlessSmsSendService.kt`：处理 `RESPOND_VIA_MESSAGE` 快速回复。

活动：

- `MainActivity.kt`：主界面，包含 `MAIN` 和 `APP_MESSAGING` 入口。
- `ComposeSmsActivity.kt`：响应 `SEND` 和 `SENDTO` Intent。

修改 `AndroidManifest.xml` 中的权限、intent filter、`exported` 标记、receiver/service
名称时要谨慎；这些内容会影响默认短信应用资格、通知操作和系统投递行为。

## 权限要求

短信相关：

- `READ_SMS`：读取短信。
- `SEND_SMS`：发送短信。
- `RECEIVE_SMS`：接收短信。
- `RECEIVE_MMS`：接收彩信。
- `RECEIVE_WAP_PUSH`：接收 WAP Push。
- `WRITE_SMS`：写入短信数据库。
- `READ_PHONE_STATE`：识别 SIM 卡。

其他：

- `INTERNET`：用于 MMS 等确实需要网络的功能。
- `POST_NOTIFICATIONS`：Android 13+ 通知权限。

## 当前开发重心

项目正从原型阶段进入核心短信功能完善阶段。优先关注：

- 默认短信应用所需组件和 manifest 声明。
- 基础短信 CRUD：读取会话、发送短信、标记已读、删除短信、标记为骚扰。
- MMS 接收、下载、解析和展示。
- 搜索、会话列表、会话详情和通知操作的可靠性。
- 双卡识别、发送和回复流程。
- 暗色模式、动态取色和原生动效的体验优化。

## 构建、版本与验证

在仓库根目录使用 Gradle Wrapper。

Debug 构建：

```bash
./gradlew assembleDebug
```

Release 构建，需要签名配置：

```bash
./gradlew assembleRelease
```

常用局部检查：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

版本管理：

- `versionCode` 基于 `git rev-list HEAD --count`。
- `versionName` 从 `gradle/libs.versions.toml` 读取，并在构建类型后缀中拼接 Git SHA。

验证建议：

- 纯 UI 或卡片渲染改动可先使用 Mock 数据界面验证。
- 涉及短信权限、默认短信角色、SIM 识别、MMS 下载、系统通知操作的改动，应在真机上验证。
- 双卡相关行为尤其需要真机测试。

## 日志风格

- 日志输出使用英文，不使用中文。
- 日志消息使用小写开头，不写成句子形式；避免首字母大写和句末标点。
- 优先使用短语和键值对，例如 `spam score message_id=123 score=0.82`。

## 相关文档

- `README.md`
- `docs/rule_generation_guide.md`
