# Pixel Text (原点短信) - Claude Code 项目配置

## 项目概述

Pixel Text 是一款纯粹的、符合原生 Android 审美的短信应用。通过本地智能解析技术，将验证码、服务短信转化为精美的 Material You 卡片。

**核心特性**：

- 🔒 所有短信解析在本地完成
- 🧩 基于正则的动态解析引擎
- 🎨 Material 3 + Dynamic Color
- 📱 支持作为默认短信应用
- 📶 双卡双待支持

## 技术栈

- **语言**: Kotlin (JVM 21)
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM + Clean Architecture
- **异步**: Coroutines + Flow
- **依赖注入**: Koin
- **数据存储**: DataStore Preferences
- **最低 SDK**: 31 (Android 12)

## 项目结构

```
app/src/main/java/vip/mystery0/pixel/text/
├── data/repository/          # 数据层实现
├── domain/
│   ├── model/               # 领域模型
│   ├── parser/              # 消息解析引擎
│   └── repository/          # 仓库接口
├── di/                      # Koin 依赖注入模块
└── ui/
    ├── message/             # 消息相关 UI
    │   ├── cards/          # 各类卡片组件
    │   └── factory/        # 卡片工厂
    ├── theme/              # Material 3 主题
    └── mock/               # Mock 数据界面
```

## 核心组件说明

### 1. 消息解析引擎 (`MessageParser`)

位于 `domain/parser/MessageParser.kt`，采用**多级漏斗过滤（Cascade Pipeline）**架构：

- **L1 索引**: `sender_equals` (发件人号码精确匹配)
- **L2 索引**: `signature_equals` (短信签名精确匹配，如 `【招商银行】`)
- **L3 索引**: `keywords` (关键词数组，如 `["验证码", "code"]`)
- **L4 兜底**: 无 fast_fail 的通用规则

**规则配置文件**: `app/src/main/assets/rules.json`

### 2. 支持的卡片类型

| 卡片类型               | 类名                        | 用途      |
|--------------------|---------------------------|---------|
| `TrainTicket`      | `TrainTicketCard.kt`      | 火车票/高铁票 |
| `BankTransaction`  | `BankTransactionCard.kt`  | 银行动账    |
| `PhoneRecharge`    | `PhoneRechargeCard.kt`    | 手机充值/交费 |
| `VerificationCode` | `VerificationCodeCard.kt` | 验证码     |
| `Flight`           | `FlightCard.kt`           | 航班信息    |
| `ExpressDelivery`  | `ExpressDeliveryCard.kt`  | 快递到达通知  |
| `NormalMessage`    | `NormalMessageCard.kt`    | 普通消息    |
| `OriginalText`     | `OriginalTextCard.kt`     | 原始文本    |

### 3. 解析规则 JSON 结构

```json
{
  "id": "业务名_特定场景_编号",
  "target_card": "目标卡片类型",
  "priority": 100,
  "fast_fail": {
    "signature_equals": "短信签名(选填)",
    "sender_equals": "发件人号码(选填)",
    "keywords": ["关键词1", "关键词2"]
  },
  "conditions": {
    "content_regex": "带有Java命名捕获组的正则表达式"
  }
}
```

**关键要求**：
- 必须使用 Java 命名捕获组 `(?<name>pattern)`
- JSON 中正则反斜杠需转义（`\d` → `\\d`）
- 每条规则必须提供 `fast_fail` 以避免性能问题

## 开发规范

### 代码风格

- 使用 Kotlin 惯用语法（data class、sealed class、extension functions）
- Compose 组件使用 `@Composable` 注解
- ViewModel 使用 `StateFlow` 暴露状态
- 异步操作使用 `viewModelScope.launch`

### 命名约定

- UI 组件：`XxxScreen.kt`、`XxxCard.kt`
- ViewModel：`XxxViewModel.kt`
- Repository：`XxxRepository.kt` (接口) + `XxxRepositoryImpl.kt` (实现)
- Model：`XxxModel.kt`

### Material 3 使用

- 优先使用 `MaterialTheme.colorScheme` 中的颜色
- 使用 `Card`、`Surface`、`Button` 等 Material 3 组件
- 遵循 Dynamic Color 规范

### 依赖注入

使用 Koin，模块定义在 `di/AppModule.kt`：

```kotlin
val appModule = module {
    single { MessageParser(androidContext()) }
    single<MessageRepository> { MessageRepositoryImpl(androidContext(), get()) }
    viewModel { MessageViewModel(get()) }
    viewModel { ConversationListViewModel(get()) }
    viewModel { ConversationDetailViewModel(get()) }
    viewModel { SearchViewModel(get()) }
}
```

## 构建与运行

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease
```

### 版本管理

- `versionCode`: 基于 `git rev-list HEAD --count`
- `versionName`: 从 `libs.versions.toml` 读取 + Git SHA

## 特殊注意事项

### 1. 权限要求

应用需要以下权限：

**短信相关**：

- `READ_SMS` - 读取短信
- `SEND_SMS` - 发送短信
- `RECEIVE_SMS` - 接收短信
- `RECEIVE_MMS` - 接收彩信
- `RECEIVE_WAP_PUSH` - 接收 WAP Push
- `WRITE_SMS` - 写入短信数据库
- `READ_PHONE_STATE` - 识别 SIM 卡

**其他**：

- `INTERNET` - 网络访问（必需）
- `POST_NOTIFICATIONS` - Android 13+ 通知权限

### 2. 默认短信应用

需实现以下组件（参考 Android 官方文档）：

**接收器**：

- `SmsReceiver` - 接收 `SMS_DELIVER_ACTION`
- `MmsReceiver` - 接收 `WAP_PUSH_DELIVER_ACTION`
- `NotificationActionReceiver` - 处理通知栏操作（标记已读/回复/删除）

**服务**：

- `HeadlessSmsSendService` - 处理 `RESPOND_VIA_MESSAGE`（快速回复）

**活动**：

- `MainActivity` - 主界面（`MAIN` + `APP_MESSAGING`）
- `ComposeSmsActivity` - 响应 `SEND` 和 `SENDTO` Intent

### 3. 解析规则更新

当需要添加新的解析规则时：
1. 参考 `docs/rule_generation_guide.md`
2. 在 `samples/` 目录添加真实短信样本
3. 更新 `app/src/main/assets/rules.json`
4. 确保 `fast_fail` 字段正确配置以优化性能

## 测试

### Mock 数据界面

`ui/mock/MockMessageScreen.kt` 提供了测试界面，可以快速验证卡片渲染效果。

### 真机测试

由于涉及短信权限和 SIM 卡识别，建议在真机上测试，尤其是双卡场景。

## 相关文档

- [解析规则生成指南](docs/rule_generation_guide.md)
- [README](README.md)

## 开发者

Mystery00 - Pixel 工具矩阵成员
