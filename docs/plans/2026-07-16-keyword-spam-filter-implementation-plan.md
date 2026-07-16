# 关键词骚扰拦截实施计划

> **执行要求：** 按任务顺序实现并逐项验证。项目不新增单元测试、仪器测试或测试依赖，使用编译、Lint、数据库检查和模拟器交互验证。

**目标：** 支持用户维护本地 SMS 正文关键词规则，命中短信统一进入现有骚扰处理链路，并在规则变化后安全重建历史匹配索引。

**架构：** 在现有 `spam.db` 中增加关键词和命中表，通过独立仓库管理规则与索引，`SpamRepository` 将模型结果和关键词命中合并为统一骚扰结果。新短信 Worker 同时执行关键词匹配和模型识别，历史索引由唯一 WorkManager 任务原子替换。Compose 管理页负责规则 CRUD、校验和重建状态展示。

**技术栈：** Kotlin、Room、Coroutines/Flow、WorkManager、Koin、Jetpack Compose、Material 3。

## 全局约束

- 仅匹配 SMS 正文，不处理 MMS。
- 匹配采用 `trim()` 后、不区分大小写的普通包含匹配，不支持正则。
- 关键词和短信内容不进行网络传输。
- 历史重建不执行自动已读或自动删除。
- 新短信命中后复用现有静默、自动已读和自动删除配置。
- 日志使用英文短语，代码注释和文档使用中文。

---

### 任务一：扩展骚扰数据库与统一查询

**文件：**

- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/db/SpamDatabase.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamRepository.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/repository/SpamRepositoryImpl.kt`

**实施：**

- [ ] 新增 `blocked_keyword` 和 `keyword_spam_match` 实体、DAO 查询和事务替换接口。
- [ ] 将数据库版本升级到 2，使用显式 Migration 创建新表和索引，保留 `spam_result`。
- [ ] 修改消息级、会话级和变化 Flow 查询，将模型阈值与关键词命中按并集合并。
- [ ] 保持“已完成模型识别”统计只查询 `spam_result`，避免关键词命中导致历史模型扫描跳过。
- [ ] 删除短信骚扰数据时同时清除对应关键词命中。
- [ ] 运行 `./gradlew :app:compileDebugKotlin`，确认 Room 代码生成和迁移声明可编译。

### 任务二：实现关键词领域仓库和历史重建任务

**文件：**

- 新建：`app/src/main/java/vip/mystery0/pixel/text/domain/model/BlockedKeyword.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/domain/spam/KeywordSpamRepository.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/data/repository/KeywordSpamRepositoryImpl.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/KeywordSpamRebuildWorker.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/KeywordSpamRebuildScheduler.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**实施：**

- [ ] 实现关键词 Flow、数量、规范化、重复检查、新增、修改和删除。
- [ ] 实现单条 SMS 匹配更新；命中写索引，未命中清除旧索引。
- [ ] 实现历史短信完整匹配计算，并在单个 Room 事务中替换全部索引。
- [ ] Worker 读取 `TelephonyDataSource.getSmsMessagesForSpamScan()`，重建成功后刷新 Smartspacer。
- [ ] Scheduler 使用唯一任务和 `APPEND_OR_REPLACE`，暴露最新 WorkInfo 状态。
- [ ] 在 Koin 注册仓库、Scheduler 和后续 ViewModel 依赖。

### 任务三：接入新短信骚扰处理链路

**文件：**

- 修改：`app/src/main/java/vip/mystery0/pixel/text/worker/SpamDetectionWorker.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/receiver/SmsReceiver.kt`

**实施：**

- [ ] Worker 先更新关键词命中，再按开关决定是否运行模型；关键词命中时仍保存可用的模型结果。
- [ ] 用“模型达到阈值或关键词命中”决定最终骚扰状态和广播分数。
- [ ] 模型关闭或失败时，关键词命中仍能进入静默和自动处理链路。
- [ ] 自动处理只作用于带延迟通知标记的新短信。
- [ ] `SmsReceiver` 在“骚扰短信不提醒”开启时延迟通知，让 Worker 有机会检查关键词；未命中时由 Worker 恢复原通知。
- [ ] 更新相关设置文案，说明模型识别与关键词规则的独立关系。

### 任务四：实现关键词管理状态与 Compose 页面

**文件：**

- 新建：`app/src/main/java/vip/mystery0/pixel/text/viewmodel/KeywordSpamViewModel.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/ui/screen/KeywordSpamSettingsScreen.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

**实施：**

- [ ] ViewModel 合并关键词列表和重建 WorkInfo，提供新增、修改、删除、重试及一次性错误状态。
- [ ] 在 ViewModel 中校验空值和忽略大小写重复，成功 CRUD 后安排重建。
- [ ] 管理页实现 Material 3 顶栏、隐私说明、空状态、关键词列表、编辑/删除操作和扩展 FAB。
- [ ] 新增/编辑对话框显示影响警告，并实时呈现校验或保存错误。
- [ ] 页面顶部显示重建运行、失败和重试状态。
- [ ] 设置页骚扰区域新增“关键词拦截”入口和规则数量摘要。
- [ ] AppNavigation 增加独立页面路由并保持现有转场。

### 任务五：模拟器端到端验证

**文件：** 无。

**实施：**

- [ ] 运行 `./gradlew :app:compileDebugKotlin :app:lintDebug :app:installDebug`。
- [ ] 从设置进入关键词拦截页，验证空状态、新增、重复校验、编辑、删除和警告文案。
- [ ] 添加能命中模拟器现有 SMS 的关键词，等待历史重建完成并验证骚扰会话列表变化。
- [ ] 删除关键词，验证模型未命中的短信恢复为普通状态。
- [ ] 通过数据库和界面确认 MMS 未写入关键词命中表。
- [ ] 截取关键词管理页面和命中后的骚扰列表效果图。
- [ ] 运行 `git diff --check`，完成最终代码审查并确认无无关改动。
