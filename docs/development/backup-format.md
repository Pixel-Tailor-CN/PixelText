# 应用数据备份与恢复

实现入口：设置 → 高级功能 → 备份与恢复。首版仅支持 SMS，不包含 MMS/附件、RCS、第三方备份格式、定时备份或应用主动上传。

## 数据流与恢复语义

备份前复用 `MessageMirrorSynchronizer.withSmsBackupSnapshot` 完成新一轮 SMS 对账并处理 SMS 脏记录；同步和快照共用镜像锁，不等待 MMS 附件。应用库通过一致读事务复制允许数据到干净的新库，包含 WAL 中已提交数据，不复制系统短信数据库、不另写 Provider 导出扫描器。

恢复在私有隔离目录中校验全部文件，从快照逐条读取 SMS，经字段白名单写入目标 Provider，然后重新对账当前镜像及重建索引。备份库不替换运行库，也不注册给当前同步器。设置、Room 与 Provider 不构成整体事务；执行中断会保留已写入数据。

同一时间只有一个操作。任务由应用作用域管理，离开页面不自动取消；需保持应用前台，不保证后台连续执行或进程死亡自动续传。重新导入会重新读取目标 Provider 并去重。

## 容器版本 1

扩展名 `.ptbackup`，ZIP 容器，固定条目：

```text
manifest.json
settings.json
databases/message_mirror.db
databases/spam.db
databases/conversation_archive.db
theme/<assetId>.webp
```

未选类别不包含对应数据。`spam.db` 既可承载规则，也可承载 SMS 放行状态；不能因文件存在就恢复整库。选中 SMS 时生成三个快照库（即使为空），仅规则时只有 spam.db，仅设置时没有数据库。

`manifest.json`：

| 字段 | 说明 |
| --- | --- |
| format / schemaVersion | `pixeltext-backup` / `1` |
| appVersion / createdAt | 来源应用版本、快照生成时间（毫秒） |
| smsSyncedAt | 本次 SMS 同步完成时间；不含 SMS 时省略 |
| sections | `SETTINGS`、`RULES`、`SMS` 的非空、无重复列表 |
| smsCount | 快照 SMS 数量 |
| mirrorVersion / spamVersion / archiveVersion | 首版分别支持 `3` / `3` / `1` |
| entries | 每个有效载荷的 name、size（字节）、sha256；不包含 manifest 自身 |

容器与数据库版本独立校验。首版只接受上述数据库版本及受支持的结构，不接受未知高版本或未声明支持的旧版本。当前没有已发布的旧备份格式，无需自动迁移；未来数据库升级须显式增加隔离读取适配或迁移及兼容验证，禁止 destructive migration。

### 数据库白名单

| 文件 | 允许业务表 | 输出数据 |
| --- | --- | --- |
| message_mirror.db | mirror_message、mirror_sms | 仅 transport=SMS 的完整父子行 |
| spam.db | blocked_keyword、sender_whitelist_rule、spam_allowed_message | 所选规则，以及选中 SMS 对应的单条放行 |
| conversation_archive.db | archived_conversation | 能关联到快照 SMS 的归档标记 |

保留表结构、源 user_version 和必要的 SQLite 自动索引/序列；Android 的标准 `android_metadata(locale TEXT)` 可以存在。允许两个确定结构的可选索引 `backup_sms_source`（sourceId 唯一索引）、`backup_sms_thread`（threadId 索引），用于避免状态关联扫描退化。无索引的同版本快照仍可读取，但 sourceId 不允许重复。

不包含 MMS 数据、附件、同步进度、脏记录、下载/清理队列、分类分数或镜像派生缓存。恢复即时重建关键词和验证码索引；模型历史分数不迁移，全量模型识别仍沿用设置页的既有入口。归档快照的 snippet、display_name、计数和 MMS 标记清空，防止混合会话彩信摘要被夹带；恢复时按目标会话重算。快照从新库生成，不能在整库副本上仅 DELETE 后打包。暂存库采用 DELETE journal 模式，关闭后只打包主文件，不包含 journal/WAL/SHM。

原 `localId` 是包内 backupId；sourceId/threadId 用于本包关联，不直接写入目标。单条放行必须核对原消息指纹，再读取目标身份重算指纹，不能直接迁移原指纹。跨应用库不保证全局事务快照，无法可靠关联时计入跳过数量。

### SMS 写回

使用地址、正文、毫秒日期、规范化箱类型的确定性 SHA-256 作为查找索引，并精确核对目标字段。null 与空字符串区分；号码不做有损归一化。匹配是一对一的多重集合匹配，保留同键真实重复数量，仅补足目标缺少的条数。目标索引和 ID 映射落盘，不将全部短信常驻内存。

- 既有匹配短信不覆盖 read/seen。
- 新消息保留正文、地址、日期、已读/已见、date_sent、locked、reply_path_present、status、subject、service_center 等允许字段。
- outbox/queued（4/6）恢复为 failed（5），不启动发送；去重同样采用该规范化类型。
- 不写旧 `_id/thread_id/sub_id` 或 creator；sub_id 使用未知值 -1，Provider 生成目标 ID/threadId。
- 草稿可保留空地址；目标 Provider 拒绝某记录时停止后续写入并报告部分结果。
- 原始 rawSnapshot 仅随镜像保留，不把所有原始列直接转换成 ContentValues。

其他应用并发改写目标 Provider 时不承诺全局 exactly-once；不同设备若改写号码或时间，保守匹配可能产生重复，但不为去重而删除现有短信。

## 可迁移设置

`settings.json` 包含类型明确的 preferences 列表、主题配置及 Smartspacer 默认三项筛选。每个 preference 为 key/type/value，type 仅 boolean/int/long/string，值以字符串编码；恢复校验完整白名单、类型、范围、枚举和通知文案。实际列表由 `BackupSettingsMapper.SPECS` 维护：

- 自动下载彩信偏好（不代表备份彩信内容）；骚扰检测、不提醒、自动操作、隔离及全文显示。
- 智能卡片、验证码快捷复制、锁屏隐藏、原文显示、自动删除及保留天数。
- 未读标记、时间显示、左右滑动操作、通知按钮文案/顺序、短信通知图标。
- 资源自动检查开关及周期。
- 明/暗主题及引用的实际背景文件；导入先生成新资产，主题配置提交失败时清理新资产。
- Smartspacer 默认普通/骚扰/归档筛选；不复制旧组件实例 ID。

不迁移资源版本、更新时间/检查时间、权限、默认短信资格、系统通知渠道、调试开关和教学提示。设置同步 commit 成功后才发布新的应用状态；主题/应用偏好/默认筛选分阶段提交，失败时可能已完成前面的阶段，不伪称整体回滚。

## 加密、验证与限额

使用 `net.lingala.zip4j:zip4j:2.11.5`，全部有效载荷连同 manifest 采用 ZIP AES-256 认证加密；不使用 ZipCrypto，不自行设计密码学协议。未加密包结构相同。ZIP 条目名称和大小等元信息可能可见；未加密包的 SHA-256 只检测损坏，不认证来源。

密码仅保存在当前操作内存，不写 SharedPreferences、日志、WorkManager Data 或 SavedStateHandle，完成后清理可变字符数组；密码输入不使用 rememberSaveable。密码丢失不可找回。

解密必须完整读取条目并验证认证码、哈希、长度、清单与实际文件集合，再以只读方式检查数据库版本/结构/完整性/外键及全部记录语义；拒绝意外表、视图、触发器、虚拟表、路径穿越、重复条目及运行态数据。不执行备份内提供的任意 SQL。所有验证通过后才展示可恢复预览，使用 token 引用私有快照，不重新读取可能已被替换的外部 URI。

限额：最多 32 条目；解压总量 4 GiB；manifest/设置各 4 MiB；SMS 最多 100 万条，单条文本及 rawSnapshot 负载最多 1 MiB；关键词最多 10 万条且原词/归一化词负载预算 4 MiB；白名单合并后最多 500 条；背景单张最多 32 MiB、解码尺寸不超过 8192×8192。超限拒绝，不截断后报告成功。

临时文件放 `noBackupFilesDir/backup-staging`，完成/取消删除，应用导航初始化备份仓库时异步清理上次遗留数据。不会进入 Android 自动备份；不承诺闪存安全擦除。SAF 输出失败尽力删除新建文件，用户仍应检查不完整目标文件。

## 恢复保护与错误

`RestoreSafetyCoordinator` 持久化恢复状态、计数和已完成类别；恢复启动与验证码清理的每个删除批次共用删除锁。历史消息写入前先在 `noBackupFilesDir/backup-protected.db` 持久化内容身份摘要，再调用 Provider，覆盖插入后记账前进程死亡的窗口。

收信 worker 在保护锁内判断当前消息是否为本次恢复的历史身份；历史消息不重放通知或自动操作。保护期间真实新收信仍可分类和通知，仅暂停其自动垃圾短信操作。锁顺序为恢复保护锁 → 白名单决策锁，保存分类结果时不得在已持有的白名单锁内再次调用会获取该锁的仓库。

恢复完成、失败或取消均需确认结果才解除保护。进程重启展示未结束恢复入口，可重新选择文件去重恢复，或保留部分数据并结束保护。重新导入重新计数；中断摘要以最后持久化进度为准，Provider 写入与应用进度不构成原子事务。结果显示新增、已存在、失败、未处理、出站转失败、关联跳过及已完成类别。

解除保护前提示后续常规验证码清理可能删除过期历史验证码，可选择关闭自动删除；不是永久豁免历史消息。权限/角色每条写入前复核；仅设置和规则操作不要求默认短信资格。

## 验证边界

本功能遵循[编码与验证规范](coding-and-validation.md)，没有新增单元测试或测试依赖。开发验证使用 API 37 模拟器、合成数据和实际 SAF 页面，覆盖加密/明文、错误密码/哈希损坏、设置/主题/规则/归档/放行往返、重复数量、草稿、出站转失败、Provider 写回、未知 SIM、批量中断重启和重新导入。

模拟器不替代 API 31、不同厂商 Provider、真实双卡和运营商环境；磁盘耗尽、权限撤回、百万条极限及全部并发删除窗口也需要独立验证。未据此宣称全设备兼容或完整发布验收。

## 依赖许可

Zip4j 2.11.5 的 Maven POM 声明 Apache License 2.0，已核对实际解析 JAR/POM；JAR 不包含额外 LICENSE/NOTICE 条目。许可正文见 [Apache-2.0](../licenses/zip4j-Apache-2.0.txt)，来源为 Apache 官方许可文本；版本来源：[Maven POM](https://repo.maven.apache.org/maven2/net/lingala/zip4j/zip4j/2.11.5/zip4j-2.11.5.pom)。未拷贝上游实现源码。
