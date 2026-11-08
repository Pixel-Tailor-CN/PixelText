# 云端发件方资料 App 端设计

## 当前实现核对（2026-11-07）

以下以当前 Android 源码为准；服务端响应示例是协议约定，不代表本轮访问或验证了服务端。原设计中的损坏版本自动回退、资料变更自动刷新和更新时间展示尚未形成完整实现，不作为已交付能力。

- `data/db/ConversationCacheDatabase.kt` 当前为 v3，保留资料 generation/state/profile/number 表；消息列表已由 `MessageMirrorDatabase` v7 的 `MirrorConversationQuery` 聚合，`MessageRepositoryImpl.enrichWithSenderProfiles()` 再批量查询资料并补充联系人名称。
- 当前没有订阅资料表变化来触发上述列表投影；安装成功会更新设置页版本状态，列表在下一次查询时读取新资料。旧会话缓存的 DAO、联表结果类型和转换函数已删除；为保持现有数据库结构兼容，仍保留 `cached_conversation` 与 `conversation_cache_metadata` 表及其实体。
- `SenderProfileRepository` 保留 active/previous 两代，但没有启动校验或自动切换 previous 的路径。头像解码失败由 `SenderAvatar` 回退默认图标；不能据此声称整个损坏版本自动恢复。
- `SenderProfileStore` 返回并入库的是私有头像**绝对路径**。ZIP 内引用仍为相对路径，号码逐字精确匹配，无全量常驻 Map。
- 通知在已有异步链路中等待本地 `findByNumber()` 查询；没有独立的资料预加载就绪状态，也不会为资料更新重建已发通知。

源码入口均相对 `app/src/main/java/vip/mystery0/pixel/text/`。历史验收清单只表示验证要求，本轮仅做文档与源码核对。

## 1. 文档范围

本文定义 PixelText Android App 对云端发件方资料的接入方案，包括：

- 请求独立的发件方资料 manifest。
- 从 CDN 下载并安全启用资料包。
- 按服务端下发的号码字符串在本地直接精确匹配。
- 合并联系人、云端资料和原始号码的展示优先级。
- 在会话列表、详情和通知中显示名称与头像。
- 第一版仅支持用户手动检查和安装更新，保留上一代资料，并在头像不可用时降级默认图标；版本自动回退尚未实现。

服务端资料录入、手动发布、COS 上传和公共接口实现位于：

```text
D:/Go/src/pixel-text-hub/docs/design/sender-profile-service-design.md
```

## 2. 服务端协议

App 使用独立接口：

```http
GET /api/v1/sender-profiles/manifest
```

成功响应示例：

```json
{
  "version": "2026.08.12.1",
  "schemaVersion": 2,
  "sha256": "7f...",
  "sizeBytes": 182340,
  "downloadUrl": "https://dl.pixeltext.mystery0.vip/sender-profiles/releases/2026.08.12.1/sender-profiles-7f....zip?s=...",
  "releaseNotes": "新增运营商和银行服务号码",
  "minAppVersionCode": 120,
  "publishedAt": "2026-08-12T08:00:00Z"
}
```

无稳定版本时服务端返回 `204 No Content`。App 将其视为正常结果，不重试、不清除当前本地资料。

Manifest 响应使用 `Cache-Control: no-store`，不提供 ETag/304。`downloadUrl` 是短期 CDN 签名地址，App 不持久化、不缓存、不跨检查任务复用该地址。每次确定需要下载资料包时，必须先请求最新 manifest，并立即使用本次响应中的 `downloadUrl`。

资料包结构：

```text
sender-profiles.zip
├── sender-profiles.json
└── avatars/
    ├── 1.webp
    ├── 2.webp
    └── 3.webp
```

JSON Schema v2：

```json
{
  "schemaVersion": 2,
  "version": "2026.08.12.1",
  "generatedAt": "2026-08-12T08:00:00Z",
  "profiles": [
    {
      "displayName": "中国移动",
      "avatar": {
        "path": "avatars/1.webp",
        "sha256": "ab..."
      },
      "numbers": ["10086", "CMCC"]
    }
  ]
}
```

第一版多号码客户端只接受 `schemaVersion=2`。每条标签包含排序后的 `numbers` 数组，名称和头像只保存一次。App 加载时展开每个号码到本地索引。资料包不再包含或解析：

- 业务 Profile ID。
- category。
- priority。
- enabled。
- matcher 类型和数组。

## 3. 总体架构

```text
设置页手动更新操作
  └─ SenderProfileRemoteDataSource
       ├─ GET 独立 manifest
       └─ 从 CDN 下载 ZIP
                |
                v
SenderProfileStore
  ├─ 大小与 SHA-256 校验
  ├─ 安全解压与 JSON/头像校验
  └─ 将头像移动到版本化私有目录
                |
                v
SenderProfileRepository
  ├─ 事务导入标签、号码和版本元数据到 Room
  ├─ 原子切换 active generation
  └─ 按号码查询资料
                |
                v
MessageMirrorDatabase 的消息聚合
  └─ MessageRepositoryImpl.enrichWithSenderProfiles
       ├─ 批量查询 ConversationCacheDatabase 的 active 资料
       └─ 合并联系人名称 → ConversationModel / 详情 / 通知
```

## 4. 领域模型

当前领域模型位于 `domain/model/SenderProfileModels.kt`：

```kotlin
data class SenderProfileMatch(
    val displayName: String,
    val avatarPath: String,
    val avatarSha256: String,
)

// 资料包使用 SenderProfileBundleItem 和 SenderProfileBundleAvatar；查询结果不重复携带全部号码。
```

Room 中的云端资料模型：

```kotlin
@Entity(tableName = "sender_profile_generation")
data class SenderProfileGenerationEntity(
    @PrimaryKey val version: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)

@Entity(tableName = "sender_profile_state")
data class SenderProfileStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "active_version") val activeVersion: String?,
    @ColumnInfo(name = "previous_version") val previousVersion: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "sender_profile",
    indices = [Index("generation_version")],
)
data class SenderProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "generation_version") val generationVersion: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String,
    @ColumnInfo(name = "avatar_sha256") val avatarSha256: String,
)

@Entity(
    tableName = "sender_profile_number",
    primaryKeys = ["generation_version", "number"],
    indices = [
        Index("generation_version"),
        Index(value = ["generation_version", "sender_profile_id"]),
    ],
)
data class SenderProfileNumberEntity(
    @ColumnInfo(name = "generation_version") val generationVersion: String,
    val number: String,
    @ColumnInfo(name = "sender_profile_id") val senderProfileId: Long,
)
```

头像仍保存在应用私有文件目录，Room 保存私有头像绝对路径和 SHA-256，不保存 WebP BLOB。App 不依赖服务端数据库 ID；客户端 Room ID 只用于本地联表。

正式实现时为 generation、profile、number 增加外键：删除 generation 时级联删除标签和号码；number 使用 `(generation_version, sender_profile_id)` 复合外键关联同 generation 的 profile，避免跨版本错误引用。`sender_profile_state` 为固定 `id=1` 的单行状态表，数据库中不使用多个 generation 的 `active` 布尔值。

## 5. 展示优先级

名称：

1. 系统联系人名称。
2. 云端发件方名称。
3. 原始发件地址。

头像：

1. 系统联系人头像，后续增强。
2. 云端 WebP 头像。
3. 当前基于地址颜色生成的默认头像。

当前版本尚未读取系统联系人头像，因此实际头像优先级为：云端 WebP 头像 > 默认头像。联系人名称命中不会屏蔽云端头像，即允许显示“联系人自定义名称 + 号码对应的云端机构头像”。后续实现联系人头像后，再自然提升到头像最高优先级。

用户联系人不能被云端名称覆盖。UI 不显示“官方认证”等安全背书。

## 6. 地址匹配

App 不做号码语义规范化，也不生成候选 Key。匹配时直接使用 Android/Telephony 提供的发件地址字符串查询服务端资料包中的号码索引：

```kotlin
profilesByNumber[address]
```

App 明确不执行：

- `+86`、`0086` 或其他国家区号截取。
- 空格、短横线、括号等分隔符删除。
- 字母 Sender ID 大小写转换。
- 手机号、固话、服务短号或共享通道号段判断。
- `PhoneNumberUtils` 模糊匹配。

如果同一机构可能以多种字符串形式出现，应由服务端将这些形式作为多个 `numbers` 绑定到同一标签，例如：

```json
{
  "displayName": "示例机构",
  "numbers": ["+8613800138000", "13800138000"]
}
```

加载资料包时以临时 `seenNumbers` 集合检查重复，导入时将每个 `profile.numbers` 元素原样写入 `sender_profile_number.number`，不进行转换；查询使用 Room DAO，不维护全量号码 Map。出现完全相同的号码字符串且指向不同标签时拒绝整个新版本，保留当前有效版本。完全相同的字符串在同一标签中重复也视为资料包非法。未精确匹配时按无云端资料处理。

## 7. 网络层

独立 manifest DTO：

```kotlin
@JsonClass(generateAdapter = true)
data class SenderProfileManifestResponse(
    val version: String,
    val schemaVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val minAppVersionCode: Int,
    val publishedAt: String?,
)
```

Retrofit：

```kotlin
@GET("api/v1/sender-profiles/manifest")
suspend fun fetchSenderProfileManifest(): Response<SenderProfileManifestResponse>
```

下载限制：

- manifest `sizeBytes` 必须大于 0 且不超过客户端上限。
- CDN `Content-Length` 存在时必须与 manifest 一致。
- 实际写入超过上限时立即终止并删除临时文件。
- 只允许 HTTPS CDN URL。
- 不持久化或复用 `downloadUrl`，不实现 manifest ETag 缓存。
- CDN 返回 401 或 403 时，重新请求一次 manifest；仅当版本、大小和 SHA-256 仍与目标版本一致时，使用新地址重试一次下载。
- 请求不得附带本地号码、联系人、短信正文或命中信息。

## 8. 本地存储与原子启用

元数据存入 `ConversationCacheDatabase`，头像存入应用私有文件：

```text
files/sender_profiles/
├── versions/
│   ├── 2026.08.12.1/
│   │   └── avatars/...
│   └── 2026.08.01.1/
│       └── avatars/...
└── tmp/
```

Room 中由单行 `sender_profile_state` 的 `active_version` 和 `previous_version` 表示当前与上一有效版本，不使用指针文件，也不在 generation 表维护多个 active 布尔值。

启用流程：

1. 下载到 `tmp/{version}.zip.part`。
2. 校验实际大小和 ZIP SHA-256。
3. 安全解压到临时目录。
4. 校验 Schema 和版本。
5. 对每条资料校验号码、名称和头像引用的文件存在。
6. 校验被引用头像的路径、图片可解码性（BitmapFactory，不额外校验实际编码必须为 WebP）、大小和 SHA-256。
7. 将头像移到 `versions/{version}/avatars/`，数据库保存最终私有文件绝对路径。
8. 开启 Room Transaction，插入新 generation、标签和号码。
9. 读取当前 state，将旧 `active_version` 写入 `previous_version`，将新版本写入 `active_version`。
10. 提交事务后设置页更新安装版本；消息列表下次聚合时批量读取新资料，当前未观察资料表触发主列表自动刷新。
11. 保留 active 和 previous generation，后台删除更老的数据库记录和头像目录。

头像正式版本目录必须在数据库事务前准备完成。数据库导入失败时 active 状态保持不变，并删除本次无引用头像目录。

### 8.1 ZIP 安全

必须防止 ZIP Slip、绝对路径、`..`、反斜线绕过、重名条目、大小写路径冲突和压缩炸弹。App 不检查头像引用唯一性，也不拒绝 ZIP 中未被 JSON 引用的头像文件。

当前已实现的客户端上限：

```text
max zip bytes: 10 MiB
max extracted bytes: 30 MiB
max entries: 6000
max avatar bytes: 256 KiB
```

ZIP 中允许：

- 根目录唯一的 `sender-profiles.json`。
- `avatars/{数字ID}.webp` 头像文件。

客户端只要求 JSON 引用的头像文件存在、可解码、大小不超过 256 KiB 且 SHA-256 一致。不要求每个头像文件都被引用，不要求头像路径只能被一个标签引用，也不检查头像是否为正方形；这些发布产物约束由服务端负责。

## 9. Room 查询与 Repository

资料仍在 `ConversationCacheDatabase` v3。`SenderProfileDao.findActiveByNumber()` 和 `findActiveByNumbers()` 联接 state、number、profile，按 active generation 与原始号码精确查询。导入通过同一库的事务切换 state，清理先删非 active/previous generation，再删其头像目录。

主列表、归档与骚扰列表先由镜像库的 `MirrorConversationQuery` 聚合消息，再由 `MessageRepositoryImpl.enrichWithSenderProfiles()` 批量补充资料与联系人名称。详情和通知使用单号码查询。搜索结果目前补充 SIM 和骚扰判定，不额外显示云端头像。

旧 `CachedConversationDao`、`CachedConversationWithSenderProfile` 及其联表观察方法已删除，旧缓存表和实体仅为数据库结构兼容而保留。当前列表没有订阅资料表变化，因此不能把资料表失效通知等同于主列表立即刷新。资料安装不调用 `fullSync()`，也不刷写 `cached_conversation`。

## 10. UI 与通知接入

### 10.1 通用头像

新增通用 `SenderAvatar` Compose 组件：

- `ContactUri`：后续通过 ContentResolver 加载。
- `LocalFile`：在 IO 线程解码本地 WebP。
- `Generated`：保留当前默认头像。

本地头像：

- 圆形 48dp。
- `ContentScale.Fit`。
- 使用 `path + contentHash` 作为缓存键。
- 解码失败回退默认头像。

### 10.2 会话列表和详情

当前阶段采用最小改造，不新增 `SenderPresentation` 或独立会话展示模型。直接扩展现有 `ConversationModel`：

```kotlin
data class ConversationModel(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val timestamp: Long,
    val displayName: String? = null,
    val unreadCount: Int = 0,
    val isMms: Boolean = false,
    val hasMms: Boolean = false,
    val avatarPath: String? = null,
    val avatarSha256: String? = null,
)
```

联表查询获得云端名称和头像后，Repository 按以下规则生成模型：

- `displayName = 联系人名称 ?: 云端名称`。
- `avatarPath`、`avatarSha256` 使用云端资料；未命中时为空。
- UI 标题继续使用 `displayName ?: address`。
- `avatarPath` 存在时显示本地 WebP，否则显示现有默认头像。
- 默认头像颜色种子仍使用原始 address。
- 详情页始终允许查看原始号码。

联系人头像当前尚未实现。第一版固定规则为：只要号码命中云端资料就显示云端头像；名称独立按联系人名称优先。因此联系人名称与云端头像可以同时出现，不再增加额外开关或判断。

`avatarPath` 和 `avatarSha256` 只是动态联表结果，不写入 `CachedConversationEntity`、`ArchivedConversationEntity` 或 Telephony Provider。

未来按短信签名分组时，分组关联单位是 `messageId`：同一 `threadId` 中的不同短信可能解析出不同签名，并分别进入不同分组详情。本次功能不为该需求预建 thread 级分组模型，后续直接重构会话列表与详情查询。

### 10.3 通知

通知标题使用解析时可获得的最终展示名称，本地头像可作为 LargeIcon。回复地址、Intent Extra 和 threadId 始终使用原始地址，不能使用展示名称。

通知在既有异步接收/Worker 链路内调用 suspend `findByNumber()`，查询当前已落库的 active generation，并尝试联系人优先展示；没有独立的资料内存加载阶段。查询失败或无匹配时降级，实际回退边界由各通知调用者处理。`SmsNotificationHelper` 使用本地头像构造发送者图标。

资料安装不主动重建已发通知；后续正常通知事件可重新读取资料。页面是否刷新取决于对应查询重跑，不承诺资料安装本身触发所有页面的 Flow。

## 11. 更新策略

第一版只支持设置页手动更新：

- 不创建 WorkManager、Worker 或 Scheduler。
- App 启动时不请求 manifest，也不自动检查更新。
- 用户点击“检查发件方资料更新”后请求最新 manifest。
- 无稳定版本、版本相同或最低 App Version Code 不满足时显示明确结果，不下载。
- 发现不同且兼容的远端版本后，展示版本、大小和发布说明，由用户确认安装。
- 用户确认后立即重新请求一次最新 manifest，确保获得新的短期 CDN 签名地址；元数据仍一致时开始下载。
- 下载、SHA、ZIP、Schema、号码或头像校验失败时保留当前版本并展示错误。
- Room state 和头像目录保留 active generation 与 previous generation，上一版本目前仅被保留，尚未接入 active 损坏检测和自动恢复。

服务端资料保存不会自动发布，因此 App 只会看到管理员手动发布后的稳定版本。业务版本存在错误时，由服务端管理后台回滚公共稳定版本；用户下次手动检查时安装回滚后的版本。

## 12. 设置项

设置页增加独立的“发件方资料”设置项，不合并到现有规则/模型资源更新入口，避免用户误认为三类资源会一并更新。

独立设置项展示和处理：

- 当前发件方资料版本。
- 当前尚不展示上次成功更新时间（generation 的 importedAt 已保存）。
- “检查发件方资料更新”操作。
- 检查中、可更新、下载进度、安装成功和失败状态。

第一版不增加自动更新开关、仅 Wi-Fi 选项或后台检查时间配置。App 不提供“恢复上一版本”设置。上一有效 generation 目前仅被保留，尚未实现 active 数据损坏或头像缺失时的自动切换。业务数据错误由服务端管理后台回滚公共稳定版本。

## 13. 安全与隐私

- 只下载完整资料包，不向服务端发送待识别号码。
- 不记录用户会话号码或命中日志。
- 只允许 HTTPS CDN URL。
- 校验 ZIP 大小、SHA-256、Schema、号码和头像哈希。
- Profile 名称不是官方认证。
- 金融机构命中时必须保留查看原始号码的能力。
- 隐私政策说明：号码匹配在本地完成，不上传短信发件地址。

英文日志示例：

```text
sender profile update check failed error=SocketTimeoutException
sender profile bundle hash mismatch version=2026.08.12.1
sender profile bundle activated version=2026.08.12.1 profiles=128
sender profile bundle rejected reason=number_conflict version=2026.08.12.1
sender avatar decode failed
```

## 14. 失败处理

| 场景 | 行为 |
| --- | --- |
| manifest 网络失败 | 保留当前版本，设置页显示检查失败，不后台重试 |
| 服务端返回 204 | 保留当前版本并返回无稳定版本提示；不持久记录检查时间 |
| App 版本过低 | 不下载，设置页提示升级 |
| CDN 返回 401/403 | 重新请求一次 manifest，元数据一致时使用新签名地址重试一次 |
| 其他 CDN 下载失败 | 删除 `.part`，保留当前版本 |
| 大小或 SHA 不匹配 | 拒绝新版本 |
| ZIP 非法或越界 | 拒绝新版本并清理临时目录 |
| Schema 不支持 | 拒绝新版本 |
| 号码非法或重复 | 拒绝整个版本 |
| 名称为空 | 拒绝整个版本 |
| 头像缺失或非法 | 拒绝整个版本 |
| active generation 数据或头像损坏 | 当前无版本级校验/自动回退；头像加载失败回退默认图标 |
| active/上一 generation 均损坏 | 尚无统一版本修复流程，不宣称自动清空 state |
| 通知无可用资料 | 使用联系人或原始号码，头像回退；不追溯更新 |
| 运行时头像解码失败 | 仅该头像回退默认图标 |

## 15. 实施顺序

1. 增加简化后的 manifest 和资料包 DTO。
2. 扩展网络层独立 manifest 请求和受限下载。
3. 实现资料包号码字符串校验和精确索引构建，不新增号码语义规范化器。
4. 实现安全 ZIP 解压、JSON/头像校验和版本头像目录。
5. 扩展 `ConversationCacheDatabase`，增加 generation、单行 state、标签、号码表、外键及 Room Migration。
6. 实现事务导入、state 的 active/previous 原子切换、旧 generation 清理、批量联表和单号码精确查询。
7. 为 `ConversationModel` 增加动态头像路径和哈希字段，增加会话联表查询并改造主列表、归档、骚扰和详情展示。
8. 实现通用本地 WebP 头像组件。
9. 改造通知链路。
10. 实现设置页手动检查、确认下载、进度和结果状态。
11. 更新隐私政策。
12. 执行编译、Lint 和真机验证。

## 16. 验收标准

- 发件方资料更新不依赖 `/api/v1/resources/manifest`；规则与模型更新仍使用该接口。
- App 只从服务端获取 manifest，ZIP 直接从 CDN 下载。
- 每条云端资料只包含号码、名称和 WebP 头像引用。
- App 不处理 category、priority、enabled 或 matcher 数组。
- 号码匹配完全在本地按原始字符串精确完成，不进行区号截取、分隔符删除或大小写转换。
- 联系人名称优先于云端名称。
- 资料已加载时，主列表、归档、骚扰列表、详情和后续通知展示一致。
- 通知等待当前异步链路内的本地资料查询，无匹配或查询异常时按调用者策略降级，不追溯更新旧通知。
- 更新失败、包损坏和无网络不影响短信基础功能。
- 资料版本变化后，当前列表是否立即刷新仍是待补齐能力；重新查询可读取新资料。
- Room 和 App 私有头像目录保留 active 与 previous generation；当前没有自动故障恢复或用户手动切换入口。
- 云端名称和头像信息不写入 `CachedConversationEntity`、`ArchivedConversationEntity` 或 Telephony Provider，只通过 active generation 联表动态获得并填充到 `ConversationModel`。
- 本次不为未来签名分组设计额外模型；后续签名分组以 `messageId` 为关联单位并单独重构。
