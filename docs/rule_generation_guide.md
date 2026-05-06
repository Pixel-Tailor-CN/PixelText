# Pixel Text 动态解析规则生成指南

这份文档用于指导 AI Agent（如 ChatGPT、Codex、Gemini 等）如何基于 `samples/` 目录中的真实短信样本，自动为
Pixel Text 项目生成和更新 `assets/rules.json` 中的解析规则。

## 1. 引擎架构与规则模型

Pixel Text 使用的是基于「多级漏斗过滤（Cascade Pipeline）」的动态工厂。当一条短信进入时，会通过 O(1) 的
`fast_fail` 机制进行预筛选，只有通过预筛选的规则才会执行极其耗时的正则表达式（`content_regex`）。

### JSON 规则结构

每一条 Rule 必须遵循以下 JSON 结构：

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

### 必读准则

- **极速拦截 (fast_fail)**：每条规则**必须**提供 `fast_fail` 字段。优先使用 `signature_equals` (如
  `招商银行`) 或 `sender_equals`。如果没有固定的发件人或签名，必须提供 `keywords` 数组 (如
  `["验证码", "code"]`) 以避免滥用正则导致的性能问题。
- **命名捕获组**：Android 原生支持 Java 标准命名捕获组 `(?<name>pattern)`。你必须使用命名捕获组，并将抓取的值与
  `target_card` 要求的字段严格对应。

## 2. 支持的卡片类型及捕获组规范

由于 UI 层采用了动态 Map 组装逻辑，你需要尽可能将有用的信息塞入指定的捕获组。

### 🎫 1. TrainTicket (火车票/高铁票)

- **支持的捕获组**：
    - `date`: 日期 (如 "5月3日", "2026-04-02")
    - `trainNo`: 车次 (如 "G1591", "C6906")
    - `departureStation`: 出发站 (如 "西安北")
    - `departureTime`: 出发时间 (如 "14:24", "10:05")
    - `arrivalStation`: 到达站 (选填)
    - `passenger`: 乘车人 (选填)
    - `seat`: 座位号/检票口 (选填)

### 💳 2. BankTransaction (银行动账)

- **支持的捕获组**：
    - `type`: 交易类型 (如 "扣款", "支出", "消费", "入账", "支付")
    - `amount`: 金额 (仅提取数字或带小数点，如 "14312.58")
    - `account`: 交易账户/尾号 (如 "0252", "1828")
    - `date`: 交易时间 (如 "04月14日00:50")
    - `details`: 交易备注/资金去向 (选填)

### 📱 3. PhoneRecharge (手机充值/交费)

- **支持的捕获组**：
    - `amount`: 充值或扣费金额
    - `date`: 充值时间 (选填)
    - `balance`: 当前可用余额 (选填)

### 🔑 4. VerificationCode (验证码)

- **支持的捕获组**：
    - `code`: 验证码文本 (通常为 4-8 位连续数字或字母组合)

## 3. 给 AI Agent 的工作流 (Workflow)

当用户请求你“根据 `samples/sms.txt` 更新规则”时，请严格执行以下步骤：

1. **分析样本**：逐行读取用户的短信文本，提取**短信签名**（被 `【】` 或 `[]` 包裹的部分），并推断业务场景（如银行、验证码、订票）。
2. **选择目标卡片**：根据场景选择最合适的 `target_card`。
3. **编写带命名捕获组的正则**：
    - 提取业务的核心动态字段，分配给对应的命名组。
    - 对冗余文字使用 `.*?` 进行模糊匹配。
    - **注意 JSON 转义**：在生成的 JSON 字符串中，正则表达式的反斜杠必须转义（如 `\d` 必须写成 `\\d`）。
4. **生成 JSON 并合并**：输出符合要求的 JSON 对象，并确保 `priority` 分配得当（越具体、越长的正则优先级越高）。

## 4. 完美示例 (Example)

**输入样本**：
`【交通银行】您尾号*1828的卡05月06日08：23在财付通手机充值-中国联通网上支付200.00元，现余额8568.75元`

**AI 输出的 JSON Rule**：

```json
{
  "id": "bank_transaction_comm",
  "target_card": "BankTransaction",
  "priority": 80,
  "fast_fail": {
    "signature_equals": "交通银行"
  },
  "conditions": {
    "content_regex": "尾号\\*?(?<account>\\d{4}).*?(?<date>\\d{2}月\\d{2}日\\d{2}：\\d{2})在(?<details>.*?)(?<type>支付|消费|扣款)(?<amount>[0-9,.]+)(?<currency>元)"
  }
}
```
