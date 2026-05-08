# Pixel Text 动态解析规则生成指南

这份文档用于指导 AI Agent（如 ChatGPT、Claude、Gemini 等）如何基于 `samples-desensitized/`目录中的脱敏短信样本，自动为
Pixel Text 项目生成和更新 `app/src/main/assets/rules.json` 中的解析规则。

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

- **必需的捕获组**：
  - `date`: 日期（如 "5月3日", "2026-04-02"）
  - `trainNo`: 车次（如 "G1591", "C6906"）
  - `departureStation`: 出发站（如 "西安北"）
  - `departureTime`: 出发时间（如 "14:24", "10:05"）
- **可选的捕获组**（会被放入 `details` Map）：
  - `arrivalStation`: 到达站
  - `arrivalTime`: 到达时间
  - `passenger`: 乘车人
  - `seat`: 座位号（如 "08车厢05A号"）
  - `seatClass`: 座位等级（如 "二等座"）
  - `gate`: 检票口（如 "23B", "A15"）

**注意**：`TrainTicket` 模型的 `details` 字段是一个 `Map<String, String>`，所有非必需字段都会被放入这个
Map 中。UI 层会自动渲染这些动态字段。

### 💳 2. BankTransaction (银行动账)

- **必需的捕获组**：
  - `type`: 交易类型（如 "扣款", "支出", "消费", "入账", "支付"）
  - `amount`: 金额（仅提取数字或带小数点，如 "14312.58"）
- **可选的捕获组**（会被放入 `details` Map）：
  - `account`: 交易账户/尾号（如 "0252", "1828"）
  - `date`: 交易时间（如 "04月14日00:50"）
  - `status`: 交易状态（如 "失败", "成功"）
  - `reason`: 失败原因（如 "卡片过期"）
  - 其他任何业务相关字段

**注意**：`BankTransaction` 模型还包含 `isSuccess` 和 `errorMessage` 字段，解析器会根据 `status`
字段自动判断交易是否成功。

### 📱 3. PhoneRecharge (手机充值/交费)

- **支持的捕获组**：
    - `amount`: 充值或扣费金额
    - `date`: 充值时间 (选填)
    - `balance`: 当前可用余额 (选填)

### 📦 5. ExpressDelivery (快递到达)

- **必需的捕获组**：
  - `code`: 取件码（如 "22-3-5041", "3-88466"）
  - `location`: 取件地点（如 "5栋1单元101号无人自助店"）
- **可选的捕获组**：
  - `company`: 快递公司（如 "中通", "韵达"）
  - `time`: 取件时限（如 "23:00"）

### 🔑 6. VerificationCode (验证码)

- **必需的捕获组**：
  - `code`: 验证码文本（通常为 4-8 位连续数字或字母组合）
- **可选的捕获组**：
  - `signature`: 短信签名（如 "支付宝", "微信"）

## 3. 给 AI Agent 的工作流 (Workflow)

当用户请求你”根据 `samples-desensitized/` 目录的短信生成规则”时，请严格执行以下步骤：

### 步骤 1：分析样本并生成规则

1. **读取脱敏样本**：逐行读取 `samples-desensitized/` 目录中的短信文本。
2. **提取短信签名**：识别被 `【】` 或 `[]` 包裹的部分作为 `signature_equals`。
3. **推断业务场景**：根据内容判断是银行、验证码、订票、快递等场景，选择合适的 `target_card`。
4. **编写带命名捕获组的正则**：
    - 提取业务的核心动态字段，分配给对应的命名组。
    - 对冗余文字使用 `.*?` 进行模糊匹配。
    - **注意 JSON 转义**：在生成的 JSON 字符串中，正则表达式的反斜杠必须转义（如 `\d` 必须写成 `\\d`）。
5. **设置优先级**：越具体、越长的正则优先级越高（建议范围 10-120）。

### 步骤 2：合并规则到 rules.json

1. **读取现有规则**：读取 `app/src/main/assets/rules.json` 文件。
2. **检查重复和相似规则**：
  - 如果存在 `id` 相同的规则，替换为新规则。
  - 如果存在 `signature_equals` 和 `keywords` 都相同的规则，尝试合并或优化正则表达式。
  - 如果是全新的业务场景，直接追加到 `rules` 数组末尾。
3. **更新文件**：将合并后的规则写回 `app/src/main/assets/rules.json`。

### 步骤 3：生成脱敏样本

如果用户提供的是真实短信样本，你需要：

1. **脱敏敏感信息**：
  - **人名**：替换为常见人名（如 “张三”, “李四”, “王五”）
  - **地址**：替换为通用地址（如 “建设路88号阳光小区3栋1单元202号”）
  - **车次号**：替换为随机车次（如 “C1234”, “G5678”）
  - **座位号**：替换为随机座位（如 “08车厢05A号”）
  - **短链接**：替换为随机字符串（如 “s.12306.cn/s/A/aBcDeF”）
  - **保留格式**：确保脱敏后的数据格式与原始数据一致，不影响正则表达式解析
2. **写入文件**：将脱敏后的短信写入 `samples-desensitized/{当前日期}_sms.txt`，日期格式为 `YYYYMMDD`（如
   `20260508_sms.txt`）。

## 4. 完美示例 (Example)

### 示例 1：银行交易短信

**输入样本（脱敏后）**：

```
【交通银行】您尾号*1828的卡05月06日08：23在财付通手机充值-中国联通网上支付200.00元，现余额8568.75元
```

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

### 示例 2：火车票短信（汇联易甄选）

**输入样本（脱敏后）**：

```
【汇联易甄选】购票成功，2026-04-02 14:24:00出发，西安北—成都东，G2345，检票口：23B，张三 二等座 08车厢05A号。服务热线：4006297878
```

**AI 输出的 JSON Rule**：

```json
{
  "id": "ticket_huilianyi_success_v2",
  "target_card": "TrainTicket",
  "priority": 105,
  "fast_fail": {
    "signature_equals": "汇联易甄选",
    "keywords": [
      "购票成功"
    ]
  },
  "conditions": {
    "content_regex": "购票成功，(?<date>\\d{4}-\\d{2}-\\d{2})\\s*(?<departureTime>\\d{2}:\\d{2}:\\d{2})出发，(?<departureStation>[^—]+)—(?<arrivalStation>[^，]+)，(?<trainNo>[A-Z0-9]+)，(?:检票口：(?<gate>[^，]+)，)?(?<passenger>[\\u4e00-\\u9fa5]+).*?(?<seatClass>\\w+座)?\\s*(?<seat>\\d+车厢\\w+号)"
  }
}
```

**说明**：

- `date`, `trainNo`, `departureStation`, `departureTime` 是必需字段
- `arrivalStation`, `gate`, `passenger`, `seatClass`, `seat` 是可选字段，会被放入 `details` Map

### 示例 3：快递到达短信

**输入样本（脱敏后）**：

```
【菜鸟驿站】您的中通包裹已到5栋1单元101号无人自助店，请23:00前凭22-3-5041扫码开门自助取件。
```

**AI 输出的 JSON Rule**：

```json
{
  "id": "express_cainiao_arrival",
  "target_card": "ExpressDelivery",
  "priority": 90,
  "fast_fail": {
    "signature_equals": "菜鸟驿站",
    "keywords": [
      "包裹已到"
    ]
  },
  "conditions": {
    "content_regex": "(?:您的?(?<company>[^包]+?)包裹|您有包裹)已到(?<location>[^，。]+)[，。](?:请(?<time>[^前]+)前)?.*?(?:凭|取件码)[：:]?(?<code>[0-9a-zA-Z-]+)"
  }
}
```

## 5. 规则合并策略

当生成新规则时，需要检查是否与现有规则冲突或重复：

### 情况 1：完全相同的 ID

- **操作**：替换旧规则

### 情况 2：相同的 signature_equals 和相似的 keywords

- **操作**：比较正则表达式的覆盖范围
  - 如果新规则更精确（捕获组更多），替换旧规则
  - 如果新规则是旧规则的子集，保留旧规则
  - 如果两者互补，保留两条规则并调整优先级

### 情况 3：全新的业务场景

- **操作**：直接追加到 `rules` 数组末尾

### 情况 4：通用兜底规则

- **操作**：优先级设置为最低（如 10），确保不会误匹配

## 6. 输出格式要求

AI Agent 在完成规则生成后，应该：

1. **输出生成的规则 JSON**：清晰展示新增或修改的规则。
2. **说明合并策略**：告知用户哪些规则被替换、哪些被保留、哪些是新增的。
3. **确认文件更新**：明确告知 `app/src/main/assets/rules.json` 已更新。
4. **生成脱敏样本**（如果需要）：告知 `samples-desensitized/{日期}_sms.txt` 已创建。

## 7. 注意事项

- **性能优先**：每条规则必须有 `fast_fail`，避免对所有短信执行正则匹配。
- **正则转义**：JSON 中的反斜杠必须转义（`\d` → `\\d`）。
- **命名捕获组**：必须使用 Java 标准命名捕获组 `(?<name>pattern)`。
- **优先级分配**：具体规则优先级高（90-120），通用规则优先级低（10-50）。
- **脱敏规范**：脱敏后的数据必须保持格式一致，不能影响正则表达式的解析能力。
