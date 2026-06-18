# Pixel Text 解析规则生成指南

这份文档用于指导 AI Agent 基于短信样本生成或更新 Pixel Text 的解析规则。文档只关注规则生成本身：如何分析短信、编写
`content_regex`、设置 `fast_fail`、合并到 `app/src/main/assets/rules.json`，以及维护脱敏样本。

规则生成时优先使用 `samples-desensitized/` 中的脱敏短信样本。如果用户提供的是真实短信文本，必须先脱敏，再生成规则和样本。

## 1. rules.json 结构

`app/src/main/assets/rules.json` 的顶层结构如下：

```json
{
  "version": 2,
  "rules": [
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
        "content_regex": "带有 Java 命名捕获组的正则表达式"
      }
    }
  ]
}
```

更新文件时必须保留顶层 `version` 字段，只合并或替换 `rules` 数组中的规则对象。除非用户明确要求升级规则格式，不要随意修改
`version`。

## 2. Rule 字段说明

### id

规则唯一标识。建议使用稳定、可读、可追踪来源的命名：

```text
业务或机构_场景_版本
```

示例：

- `bank_cmb_transaction_v2`
- `ticket_12306_success_v2`
- `verification_oppo_open_platform`
- `express_delivery_arrival`

如果新规则覆盖旧规则，应复用原 `id` 并替换规则内容；如果是兼容新增格式，使用新的 `id`。

### target_card

当前规则生成可以使用的目标卡片：

| target_card        | 用途          |
|--------------------|-------------|
| `TrainTicket`      | 火车票 / 高铁票  |
| `BankTransaction`  | 银行动账        |
| `ExpressDelivery`  | 快递到达通知      |
| `PhoneRecharge`    | 手机充值 / 交费   |
| `VerificationCode` | 验证码         |

不要为规则生成使用 `Flight`、`NormalMessage`、`OriginalText`、`SpamMessage`、`MmsImage`。这些不是当前解析规则的生成目标。

### priority

优先级用于决定同类规则内部的匹配顺序。更具体、误匹配风险更低的规则应使用更高优先级。

建议范围：

- 90-120：机构明确、格式稳定、字段完整的规则。
- 50-89：场景明确但格式可能有少量变化的规则。
- 10-49：通用兜底规则。

不要依赖 `priority` 解决所有冲突。规则是否被执行还取决于 `fast_fail` 类型和短信是否通过预筛选。

### fast_fail

每条生产规则都必须包含有效的 `fast_fail`，避免所有短信都进入正则匹配。

优先级建议：

1. `sender_equals`：发件人号码或服务号稳定时优先使用。
2. `signature_equals`：短信签名稳定时使用，例如 `招商银行`、`12306`。
3. `keywords`：没有稳定号码或签名时使用，关键词应能明显限定业务场景。

注意：

- `signature_equals` 只填写签名文本，不包含 `【】` 或 `[]`。
- `keywords` 是关键词数组，至少一个关键词命中即可通过预筛选。
- 如果一条规则同时填写 `signature_equals` 和 `keywords`，不要把它理解为“双重门槛”。关键词可以作为人工阅读时的场景提示，但真正的内容约束必须写进 `content_regex`。
- 兜底规则也必须有 `fast_fail`，通常使用通用但必要的关键词，例如验证码规则使用 `验证码`、`动态码`、`校验码`、`code`。

### conditions.content_regex

`content_regex` 必须使用 Java / Android 可编译的正则表达式，并使用 Java 命名捕获组：

```regex
(?<name>pattern)
```

JSON 中必须正确转义反斜杠，例如：

```json
{
  "content_regex": "(?<code>\\d{4,8})"
}
```

编写原则：

- 只捕获当前 `target_card` 会消费的字段。
- 用非贪婪匹配 `.*?` 跨过不重要文本。
- 优先写稳定锚点，例如固定文案、标点、金额单位、车次格式。
- 避免过宽的 `.*` 和没有边界的数字捕获。
- 正则不要求全文匹配，但必须能在完整短信内容中找到目标片段。

## 3. 捕获组规范

命名捕获组必须和目标卡片实际消费的字段一致。未被消费的捕获组不会自动展示，生成规则时不要依赖额外字段。

### TrainTicket

用于火车票 / 高铁票通知。

主要字段：

| 捕获组                 | 要求 | 说明                    |
|---------------------|----|-----------------------|
| `date`              | 必需 | 日期，例如 `5月3日`、`2026-04-02` |
| `trainNo`           | 必需 | 车次，例如 `G1591`、`C6906`     |
| `departureStation`  | 必需 | 出发站                   |
| `departureTime`     | 必需 | 出发时间，例如 `14:24`         |
| `arrivalStation`    | 可选 | 到达站，未捕获时显示 `--`        |
| `passenger`         | 可选 | 乘车人，展示为 `乘车人`          |
| `seatClass`         | 可选 | 席别，展示为 `席别`            |
| `seat`              | 可选 | 座位，展示为 `座位`            |
| `gate`              | 可选 | 检票口，展示为 `检票口`          |
| `orderNo`           | 可选 | 订单号，展示为 `订单号`          |

注意：

- 当前规则解析不会消费 `arrivalTime`，不要把它作为规则生成目标字段。
- 当前 `trainType` 固定为 `高铁`，不需要捕获。
- 如果短信只有出发站和出发时间，也可以生成规则；缺失字段会用默认值展示，但正则仍应尽量捕获核心字段。

### BankTransaction

用于银行入账、扣款、消费、代收、失败交易等通知。

主要字段：

| 捕获组       | 要求 | 说明                         |
|-----------|----|----------------------------|
| `type`    | 必需 | 交易类型，例如 `扣款`、`消费`、`入账`、`代收交易` |
| `amount`  | 必需 | 金额，只捕获数字、逗号和小数点             |
| `account` | 可选 | 卡号、账户尾号或账号片段，展示为 `交易账户`      |
| `date`    | 可选 | 交易时间，展示为 `交易时间`             |
| `details` | 可选 | 交易备注，展示为 `交易备注`             |
| `status`  | 可选 | 如果捕获值为 `失败`，卡片会按失败交易展示      |
| `reason`  | 可选 | 失败原因，失败交易时展示为 `失败原因`        |

注意：

- `amount` 不要包含 `人民币`、`RMB`、`元` 等单位。
- `type` 会用于判断金额方向。包含 `入账`、`收入`、`存入`、`退款`、`退回` 时显示为收入；包含 `扣款`、`消费`、`支出`、`支付`、`转出`、`代收` 时显示为支出。
- `currency`、`merchant` 等额外捕获组当前不会被展示。需要展示商户或业务备注时，合并捕获到 `details`。

### ExpressDelivery

用于快递到达和取件通知。

| 捕获组        | 要求 | 说明                   |
|------------|----|----------------------|
| `code`     | 必需 | 取件码，例如 `22-3-5041`、`3-88466` |
| `location` | 必需 | 取件地点                 |
| `company`  | 可选 | 快递公司，未捕获时会回退到短信签名     |
| `time`     | 可选 | 取件时限或时间              |

### PhoneRecharge

用于手机充值、话费缴费、余额通知。

| 捕获组       | 要求 | 说明                  |
|-----------|----|---------------------|
| `amount`  | 必需 | 充值或缴费金额，不包含 `元`      |
| `date`    | 可选 | 充值或缴费时间，展示为 `充值时间` |
| `balance` | 可选 | 当前余额，只捕获数字，展示时会追加 `元` |

### VerificationCode

用于验证码短信。

| 捕获组    | 要求 | 说明                         |
|--------|----|----------------------------|
| `code` | 必需 | 验证码，通常为 4-8 位数字或字母数字组合 |

注意：

- 不需要捕获 `signature`。验证码卡片的签名来自短信首尾的 `【】` 或 `[]`。
- 通用验证码规则应保持低优先级，避免抢占机构专用规则。

## 4. 规则生成工作流

当用户请求根据短信样本生成规则时，按以下步骤执行。

### 步骤 1：读取样本

1. 优先读取 `samples-desensitized/` 中的脱敏样本。
2. 如果用户提供真实短信，先脱敏，再作为规则来源。
3. 将内容相似、格式相近的短信归为同一组，不要为每条短信机械生成一条规则。

### 步骤 2：分析短信特征

对每组短信提取：

- 业务机构或签名，例如 `招商银行`、`12306`。
- 稳定发件人号码或服务号，如果样本中提供。
- 业务场景，例如验证码、银行扣款、银行入账、购票成功、快递到达。
- 固定文案和可变字段边界。
- 必需字段是否都能稳定捕获。

### 步骤 3：选择 target_card 和捕获组

根据业务场景选择 `target_card`，并只使用对应卡片规范中的捕获组。

如果短信字段不足以生成结构化卡片，应不要强行生成规则。可以向用户说明缺少哪些字段，或建议继续收集样本。

### 步骤 4：编写 fast_fail

1. 有稳定发件人时，使用 `sender_equals`。
2. 有稳定签名时，使用 `signature_equals`。
3. 没有稳定发件人或签名时，使用能限定业务场景的 `keywords`。
4. 避免只使用过于宽泛的关键词，例如单独的 `通知`、`成功`、`短信`。

### 步骤 5：编写 content_regex

1. 从固定文案开始定位。
2. 使用命名捕获组提取动态字段。
3. 对金额、日期、车次、取件码等字段设置合理边界。
4. 处理中英文冒号、逗号、句号等常见变体。
5. 在 JSON 中转义正则反斜杠。

### 步骤 6：合并规则

1. 读取现有 `app/src/main/assets/rules.json`。
2. 保留顶层 `version`。
3. 如果 `id` 相同，替换旧规则。
4. 如果 `signature_equals`、`sender_equals` 或 `keywords` 高度相似，比较覆盖范围：
   - 新规则更精确且覆盖旧样本时，替换旧规则。
   - 新规则只是旧规则子集时，保留旧规则。
   - 两者覆盖互补时，保留两条规则，并调整优先级。
5. 全新业务场景追加到 `rules` 数组末尾。

### 步骤 7：维护脱敏样本

如果规则来自真实短信，必须生成或更新脱敏样本。

脱敏要求：

- 人名替换为 `张三`、`李四`、`王五` 等。
- 地址替换为格式相近的通用地址。
- 账号、卡号、订单号、手机号等替换为同格式随机值。
- 金额、日期、车次、座位号、取件码可替换为同格式示例值。
- 短链接替换为同格式随机短链接。
- 保留原始格式、标点和字段顺序，确保正则仍可验证。

样本文件命名：

```text
samples-desensitized/YYYYMMDD_sms.txt
```

## 5. 示例

### 示例 1：银行交易短信

脱敏样本：

```text
【招商银行】您账户0252于04月14日00:50银联扣款人民币14312.58元（银联在线支付，银联转账（云闪付）-李四）
```

规则：

```json
{
  "id": "bank_cmb_transaction_v2",
  "target_card": "BankTransaction",
  "priority": 95,
  "fast_fail": {
    "signature_equals": "招商银行"
  },
  "conditions": {
    "content_regex": "账户(?<account>\\d{4})于(?<date>\\d{2}月\\d{2}日\\d{2}:\\d{2})(?<type>[^，。人]+?)[，]?人民币(?<amount>[0-9,.]+)(?:元)?[。，（\\(]?(?<details>[^）\\)]*)"
  }
}
```

### 示例 2：失败交易短信

脱敏样本：

```text
您的信用卡9352于2026年03月27日消费RMB222.02元，因卡片过期导致交易失败【中国银行】
```

规则：

```json
{
  "id": "bank_boc_credit_fail",
  "target_card": "BankTransaction",
  "priority": 90,
  "fast_fail": {
    "signature_equals": "中国银行",
    "keywords": [
      "信用卡",
      "交易失败"
    ]
  },
  "conditions": {
    "content_regex": "信用卡(?<account>\\d+)于(?<date>\\d{4}年\\d{2}月\\d{2}日)(?<type>消费)RMB(?<amount>[0-9,.]+)元[，。].*?因(?<reason>.*?)导致交易(?<status>失败)"
  }
}
```

### 示例 3：火车票短信

脱敏样本：

```text
【汇联易甄选】购票成功，2026-04-02 14:24:00出发，西安北—成都东，G2345，检票口：23B，张三 二等座 08车厢05A号。服务热线：4006297878
```

规则：

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

### 示例 4：快递到达短信

脱敏样本：

```text
【菜鸟驿站】您的中通包裹已到5栋1单元101号无人自助店，请23:00前凭22-3-5041扫码开门自助取件。
```

规则：

```json
{
  "id": "express_delivery_arrival",
  "target_card": "ExpressDelivery",
  "priority": 90,
  "fast_fail": {
    "keywords": [
      "包裹已到"
    ]
  },
  "conditions": {
    "content_regex": "(?:您的?(?<company>[^包]+?)包裹|您有包裹)已到(?<location>[^，。]+)[，。](?:请(?<time>[^前]+)前)?.*?(?:凭|取件码)[：:]?(?<code>[0-9a-zA-Z-]+)"
  }
}
```

### 示例 5：验证码短信

脱敏样本：

```text
【OPPO】123456（OPPO开放平台验证码），三分钟内有效。
```

规则：

```json
{
  "id": "verification_oppo_open_platform",
  "target_card": "VerificationCode",
  "priority": 90,
  "fast_fail": {
    "signature_equals": "OPPO",
    "keywords": [
      "验证码"
    ]
  },
  "conditions": {
    "content_regex": "(?<code>[0-9a-zA-Z]{4,8})（OPPO开放平台验证码）"
  }
}
```

## 6. 生成后的校验清单

完成规则生成后，必须检查：

- `rules.json` 是合法 JSON。
- 顶层 `version` 被保留，`rules` 是数组。
- 每条规则都有唯一 `id`。
- 每条规则都有有效 `fast_fail`。
- `target_card` 属于当前支持的规则生成目标。
- `content_regex` 能被 Java / Android 正则编译。
- 命名捕获组属于对应 `target_card` 的已消费字段。
- 必需字段能从样本中稳定捕获。
- 更具体的规则优先级高于通用规则。
- 来自真实短信的规则已同步新增或更新脱敏样本。

AI Agent 输出结果时应说明：

1. 新增、替换或保留了哪些规则。
2. 每条规则覆盖的短信样本或业务场景。
3. 是否更新了 `app/src/main/assets/rules.json`。
4. 是否新增或更新了 `samples-desensitized/` 中的样本文件。
