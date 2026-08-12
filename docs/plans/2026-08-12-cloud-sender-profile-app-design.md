# 云端发件方资料 App 端设计

## 1. 文档范围

本文定义 PixelText Android App 对云端发件方资料的接入方案，包括：

- 请求独立的发件方资料 manifest。
- 从 CDN 下载并安全启用资料包。
- 按规范化号码在本地精确匹配。
- 合并联系人、云端资料和原始号码的展示优先级。
- 在会话列表、详情和通知中显示名称与头像。
- 后台自动更新、手动更新、本地回退和失败降级。

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
  "schemaVersion": 1,
  "sha256": "7f...",
  "sizeBytes": 182340,
  "downloadUrl": "https://dl.pixeltext.mystery0.vip/sender-profiles/releases/2026.08.12.1/sender-profiles-7f....zip?s=...",
  "releaseNotes": "新增运营商和银行服务号码",
  "minAppVersionCode": 120,
  "publishedAt": "2026-08-12T08:00:00Z"
}
```

无稳定版本时服务端返回 `204 No Content`。App 将其视为正常结果，不重试、不清除当前本地资料。

资料包结构：

```text
sender-profiles.zip
├── sender-profiles.json
└── avatars/
    ├── 1.webp
    ├── 2.webp
    └── 3.webp
```

JSON Schema v1：

```json
{
  "schemaVersion": 1,
  "version": "2026.08.12.1",
  "generatedAt": "2026-08-12T08:00:00Z",
  "profiles": [
    {
      "number": "10086",
      "displayName": "中国移动",
      "avatar": {
        "path": "avatars/1.webp",
        "sha256": "ab..."
      }
    }
  ]
}
```

第一版客户端只接受 `schemaVersion=1`。每条资料只有一个号码，不再包含或解析：

- 业务 Profile ID。
- category。
- priority。
- enabled。
- matcher 类型和数组。

## 3. 总体架构

```text
SenderProfileUpdateWorker
  └─ SenderProfileRemoteDataSource
       ├─ GET 独立 manifest
       └─ 从 CDN 下载 ZIP
                |
                v
SenderProfileStore
  ├─ 大小与 SHA-256 校验
  ├─ 安全解压与 JSON/头像校验
  ├─ 原子切换 current version
  └─ 保留上一有效版本
                |
                v
SenderProfileRepository
  ├─ Map<规范化号码, SenderProfile>
  ├─ 当前版本 StateFlow
  └─ resolve(address)
                |
                v
SenderPresentationResolver
  ├─ 联系人名称/头像
  ├─ 云端名称/头像
  └─ 原始号码/默认头像
                |
                v
会话列表 / 详情 / 通知 / 其他入口
```

## 4. 领域模型

建议新增：

```kotlin
data class SenderProfile(
    val number: String,
    val displayName: String,
    val avatar: SenderProfileAvatar,
)

data class SenderProfileAvatar(
    val localPath: String,
    val sha256: String,
)
```

统一展示模型：

```kotlin
data class SenderPresentation(
    val address: String,
    val displayName: String,
    val avatar: SenderAvatar,
    val cloudNumber: String? = null,
    val cloudDisplayName: String? = null,
    val source: SenderPresentationSource,
)

sealed interface SenderAvatar {
    data class ContactUri(val uri: String) : SenderAvatar
    data class LocalFile(val path: String, val contentHash: String) : SenderAvatar
    data class Generated(val seed: String) : SenderAvatar
}

enum class SenderPresentationSource {
    CONTACT,
    CLOUD_PROFILE,
    RAW_ADDRESS,
}
```

App 不依赖服务端数据库 ID。头像路径只作为资料包内的相对文件路径使用。

## 5. 展示优先级

名称：

1. 系统联系人名称。
2. 云端发件方名称。
3. 原始发件地址。

头像：

1. 系统联系人头像，后续增强。
2. 云端 WebP 头像。
3. 当前基于地址颜色生成的默认头像。

用户联系人不能被云端名称覆盖。UI 不显示“官方认证”等安全背书。

## 6. 地址规范化与匹配

客户端规范化规则必须与服务端一致：

1. Unicode NFKC。
2. 去除首尾空白。
3. 字母型 Sender ID 转为大写。
4. 数字号码去除空格、短横线和括号。
5. `+86` / `0086` 只在剩余号码满足明确中国手机或固话格式时去除。
6. 不自动把任意 `86` 前缀短号改写为服务短号。
7. 拒绝空值、控制字符和异常超长值。
8. 拒绝 `106`、`1069` 等共享通道前缀。

加载资料包时构建：

```kotlin
Map<String, SenderProfile>
```

键为再次规范化和校验后的 `profile.number`。出现重复键时拒绝整个新版本，保留当前有效版本。

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
    val publishedAt: String,
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
- 请求不得附带本地号码、联系人、短信正文或命中信息。

## 8. 本地存储与原子启用

```text
files/sender_profiles/
├── current-version
├── previous-version
├── versions/
│   ├── 2026.08.12.1/
│   │   ├── sender-profiles.json
│   │   └── avatars/...
│   └── 2026.08.01.1/...
└── tmp/
```

启用流程：

1. 下载到 `tmp/{version}.zip.part`。
2. 校验实际大小和 ZIP SHA-256。
3. 安全解压到临时目录。
4. 校验 Schema 和版本。
5. 对每条资料校验号码、名称和头像引用。
6. 校验头像路径、WebP 格式、尺寸、大小和 SHA-256。
7. 构建完整号码索引并确认没有重复。
8. 原子移动版本目录并切换 current pointer。
9. 保存 previous pointer。
10. 通知 Repository 刷新不可变 Snapshot。

### 8.1 ZIP 安全

必须防止 ZIP Slip、绝对路径、`..`、反斜线绕过、重名条目、大小写冲突、压缩炸弹和未引用异常文件。

建议客户端上限：

```text
max zip bytes: 10 MiB
max extracted bytes: 30 MiB
max entries: 6000
max profiles: 5000
max avatar bytes: 256 KiB
max avatar dimensions: 1024x1024
```

允许的 ZIP 文件只有：

- `sender-profiles.json`
- JSON 中引用的 `avatars/{数字ID}.webp`

## 9. Repository

```kotlin
interface SenderProfileRepository {
    val snapshot: StateFlow<SenderProfileSnapshot>
    fun resolve(address: String): SenderProfile?
    suspend fun reload()
}

data class SenderProfileSnapshot(
    val version: String?,
    val profilesByNumber: Map<String, SenderProfile>,
)
```

新版本启用后一次性替换不可变 Snapshot，避免 UI 读取半更新索引。

`SenderPresentationResolver` 提供：

```kotlin
fun resolve(address: String): SenderPresentation
fun observe(address: String): Flow<SenderPresentation>
```

会话 Flow 与 `SenderProfileRepository.snapshot` 使用 `combine`，资料更新后当前页面自动刷新，不修改 Telephony Provider 或本地短信数据库。

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

- 会话标题使用 `SenderPresentation.displayName`。
- 头像使用通用组件。
- 默认头像颜色种子仍使用原始 address。
- 详情页始终允许查看原始号码。
- 联系人名称优先于云端名称。

### 10.3 通知

通知标题使用最终展示名称，本地头像可作为 LargeIcon。回复地址、Intent Extra 和 threadId 始终使用原始地址，不能使用展示名称。

## 11. 更新策略

发件方资料独立自动更新：

- WorkManager 每 24 小时检查一次。
- App 启动时若超过 24 小时未检查，安排立即检查。
- 只有远端版本不同才下载。
- `204`、版本相同和 App 版本过低按成功结束。
- 下载、SHA、ZIP、Schema、号码或头像校验失败时保留当前版本。
- 保留 current 和 previous 两个有效版本。

服务端资料保存不会自动发布，因此 App 只会看到管理员手动发布后的稳定版本。

## 12. 设置项

设置页增加：

- 当前发件方资料版本。
- 上次成功更新时间。
- 上次检查时间。
- 自动更新开关。
- 可选仅 Wi-Fi 更新。
- 立即检查更新。
- 恢复上一版本。

App 的“恢复上一版本”只切换本地 pointer，不请求服务端回滚。服务端公共版本回滚由管理后台负责。

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
sender avatar decode failed number=10086
sender profile fallback activated version=2026.08.01.1
```

## 14. 失败处理

| 场景 | 行为 |
| --- | --- |
| manifest 网络失败 | 保留当前版本，Worker 退避重试 |
| 服务端返回 204 | 保留当前状态，按成功记录检查时间 |
| App 版本过低 | 不下载，设置页提示升级 |
| CDN 下载失败 | 删除 `.part`，保留当前版本 |
| 大小或 SHA 不匹配 | 拒绝新版本 |
| ZIP 非法或越界 | 拒绝新版本并清理临时目录 |
| Schema 不支持 | 拒绝新版本 |
| 号码非法或重复 | 拒绝整个版本 |
| 名称为空 | 拒绝整个版本 |
| 头像缺失或非法 | 拒绝整个版本 |
| current 损坏 | 自动尝试 previous |
| current/previous 均损坏 | 回退默认展示 |
| 运行时头像解码失败 | 仅该头像回退默认图标 |

## 15. 实施顺序

1. 增加简化后的 manifest 和资料包 DTO。
2. 扩展网络层独立 manifest 请求和受限下载。
3. 实现号码规范化器。
4. 实现安全 ZIP 解压、JSON/头像校验和原子启用。
5. 实现 Repository 和号码 Map Snapshot。
6. 实现 SenderPresentationResolver。
7. 改造会话列表、归档、骚扰和详情展示。
8. 实现通用本地 WebP 头像组件。
9. 改造通知链路。
10. 实现独立 Worker、Scheduler 和设置项。
11. 更新隐私政策。
12. 执行编译、Lint 和真机验证。

## 16. 验收标准

- App 不依赖 `/api/v1/resources/manifest`。
- App 只从服务端获取 manifest，ZIP 直接从 CDN 下载。
- 每条云端资料只包含号码、名称和 WebP 头像引用。
- App 不处理 category、priority、enabled 或 matcher 数组。
- 号码匹配完全在本地完成。
- 联系人名称优先于云端名称。
- 主列表、归档、骚扰列表、详情和通知展示一致。
- 更新失败、包损坏和无网络不影响短信基础功能。
- 当前页面可响应资料版本变化并自动刷新。
- App 私有目录至少保留当前和上一有效版本。
