# AGENTS.md

本文件为在此仓库中工作的 AI 编码 Agent 提供项目上下文与开发约束。

## 项目概览

Pixel Text 是一款面向 Pixel 原生体验的 Android SMS/MMS 应用。项目重点是原生 Android 审美、Material
You、默认短信应用支持、双卡行为、本地服务短信智能解析，以及隐私优先的端侧处理。

- 根项目：`PixelText`
- Android 模块：`:app`
- 包名 / namespace：`vip.mystery0.pixel.text`
- 最低 SDK：31
- Target / Compile SDK：由 `gradle/libs.versions.toml` 管理
- JVM target：21

## 技术栈

- Kotlin
- Jetpack Compose UI
- Material 3 与 Dynamic Color
- MVVM + Clean Architecture
- Coroutines 与 Flow
- Koin 依赖注入
- DataStore Preferences
- Android Telephony providers 作为 SMS/MMS 数据源
- TensorFlow Lite 垃圾短信分类

## 仓库结构

- `app/src/main/java/vip/mystery0/pixel/text/`：应用代码
- `app/src/main/assets/rules.json`：短信解析规则
- `app/src/main/assets/spam_classifier.tflite`：垃圾短信分类模型
- `docs/rule_generation_guide.md`：解析规则生成指南
- `samples/`：原始短信样本，按敏感数据处理
- `samples-desensitized/`：脱敏样本，规则开发优先使用
- `gradle/libs.versions.toml`：依赖与 SDK 版本

主要包结构：

- `data/repository/`：仓库实现与系统数据访问
- `domain/model/`：领域模型
- `domain/parser/`：本地短信解析引擎
- `domain/repository/`：仓库接口
- `domain/spam/`：垃圾短信分类领域接口
- `di/`：Koin 模块
- `mms/`、`receiver/`、`service/`、`worker/`、`notification/`：Android 系统集成点
- `ui/`：Compose 界面、卡片、导航、主题与 Mock UI

## 开发规则

- UI 必须使用 Jetpack Compose，不要新增 XML layout 文件。
- 优先沿用现有架构与包边界，再考虑新增抽象。
- UI 层只负责渲染状态与处理用户交互。解析和业务逻辑放在 domain / use-case 风格代码中，系统访问放在
  repository 中。
- ViewModel 状态使用 `StateFlow` 暴露，异步任务使用 `viewModelScope.launch`。
- 依赖注入在 `di/AppModule.kt` 中通过 Koin 配置。
- 使用 Material 3 组件和 `MaterialTheme.colorScheme`，保留 Dynamic Color 行为。
- 不开发 RCS 逻辑。本项目只面向传统 SMS/MMS。
- 智能短信解析应保持本地化，不要引入网络解析或分析统计。
- `INTERNET` 权限仅在 SMS/MMS 功能确实需要网络时可以使用，例如 MMS 下载与相关处理。
- 短信样本按敏感信息处理。开发和文档优先使用 `samples-desensitized/`。

## 解析规则

解析器使用 cascade fast-fail pipeline。编辑 `app/src/main/assets/rules.json` 时：

- 先阅读 `docs/rule_generation_guide.md`。
- 每条规则都必须包含 `fast_fail`。
- 优先使用 `signature_equals` 或 `sender_equals`；没有稳定签名或号码时使用 `keywords`。
- 使用 Java 命名捕获组：`(?<name>pattern)`。
- JSON 中正确转义正则反斜杠，例如 `\\d`。
- 捕获组名称必须匹配目标卡片模型的字段要求。
- 具体规则的优先级应高于宽泛兜底规则。
- 如果规则来自真实短信文本，需要新增或更新脱敏样本。

支持的卡片目标包括：

- `TrainTicket`
- `BankTransaction`
- `PhoneRecharge`
- `VerificationCode`
- `Flight`
- `ExpressDelivery`
- `NormalMessage`
- `OriginalText`
- `SpamMessage`
- `MmsImage`

## 构建与验证

在仓库根目录使用 Gradle Wrapper：

```bash
./gradlew assembleDebug
```

Release 构建需要签名配置：

```bash
./gradlew assembleRelease
```

常用的局部检查：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

由于应用依赖短信权限、默认短信角色、SIM 状态和 MMS 投递行为，修改相关区域后应在真机上做最终验证。

## Android 组件

默认短信应用支持依赖以下组件：

- `receiver/SmsReceiver.kt`
- `receiver/MmsReceiver.kt`
- `receiver/NotificationActionReceiver.kt`
- `service/HeadlessSmsSendService.kt`
- `ComposeSmsActivity.kt`
- `MainActivity.kt`

修改 manifest intent filter、权限、exported 标记、receiver/service 名称时要谨慎；这些内容会影响默认短信应用资格和通知操作。

## 风格说明

- 遵循 Kotlin 惯用写法：data class、sealed 类型、必要时使用 extension function。
- Compose 函数应保持足够小，便于阅读和推断预览效果。
- 除非现有动态卡片 details 模式确实需要 Map，否则优先使用清晰的模型字段。
- 避免无关格式化改动。
- 不要提交生成的构建产物或本地 IDE / cache 文件。
