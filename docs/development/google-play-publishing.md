# Google Play 内部测试自动发布

## 触发与构建

[内部测试发布工作流](../../.github/workflows/google-play-internal.yml)仅监听 `main` 主分支的 push，仅当本次推送最后一个提交的信息包含 `ci`（不区分大小写、按子串匹配）时运行。例如 `ci: 发布内部测试版本` 或 `fix: 修复卡片展示 [ci]`。

一次 push 包含多个提交时，仅检查最后一个提交；不会逐个提交构建。其他分支推送、删除分支、推送标签不会触发此流程。不带 `ci` 的推送会跳过此工作流的发布任务；独立的 PR Release 运行检测工作流不受影响。GitHub 自带的 `[skip ci]` 等跳过指令仍然生效。

流程保留资源同步、完整 Git 历史和已有 Release 签名配置，执行 `:app:bundleRelease`，通过 `r0adkll/upload-google-play@v1` 将 AAB 发布到 `internal` 渠道，状态为 `completed`。此工作流不提取 APK，不创建 GitHub Release。原有 [标签 Release 工作流](../../.github/workflows/release.yml) 保持不变，推送 `v*` 标签仍会构建并创建 GitHub Release。

Play Console 发布名称采用 `版本号 (versionCode) · CI 年月日时分秒`，例如 `1.3.1 (411) · CI 2026-11-07 14:07:30`。版本号读取 `gradle/libs.versions.toml`，versionCode 使用 Git 提交数，时间记录本次 CI 开始构建 AAB 前的北京时间（UTC+8）。此名称仅用于标识内部测试发布，不改变 AAB 内的版本信息。

主分支发布任务共用并发组，不取消正在执行的发布，使用 `queue: max` 最多保留 100 个等待任务，超出平台上限的任务会被取消。此队列避免 Play 编辑事务并发，不解决重复上传同一版本号的问题。

## 一次性配置

### 1. Google Cloud 项目和 API

Google Cloud 项目是 API、服务账号和权限的管理空间，独立于 Play Console 应用；无需部署服务器。

1. 打开 [Google Cloud Console](https://console.cloud.google.com/)，通过顶部项目选择器选择已有项目，或创建例如 `PixelText Publishing` 的项目，记录项目 ID。
2. 在该项目中打开 [Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com)，点击启用。此流程不需要创建 OAuth 同意屏幕或 OAuth 客户端。
3. 打开 [IAM 和管理 → 服务账号](https://console.cloud.google.com/iam-admin/serviceaccounts)，创建例如 `pixeltext-github-publisher` 的服务账号。发布所需权限在 Play Console 授予，无需为此授予 Cloud 项目 Owner/Editor 角色。
4. 记录服务账号邮箱，例如 `pixeltext-github-publisher@PROJECT_ID.iam.gserviceaccount.com`。

Google 官方已不再要求将 Play 开发者账号关联到 Cloud 项目，参见 [API 入门说明](https://developers.google.com/android-publisher/getting_started)。

### 2. Play Console 授权

1. 打开 [Play Console](https://play.google.com/console/) → 用户和权限 → 邀请新用户，填入服务账号邮箱。
2. 仅添加 Pixel Text（`vip.mystery0.pixel.text`）的应用权限，授予“查看应用信息（只读）”和“向测试轨道发布应用”。不需要授予生产发布、财务或管理员权限。
3. 在应用的内部测试页面配置测试人员及加入测试链接；发布工具不负责维护名单。
4. 核对“应用完整性”中的上传证书与 CI keystore 一致；当前已有正式版本，无需重新创建应用或首次上传。

具体权限定义见 [Play Console 权限说明](https://support.google.com/googleplay/android-developer/answer/9844686?hl=zh-Hans)。

### 3. GitHub Secret

在 Cloud 服务账号详情 → 密钥 → 添加密钥 → 创建新密钥中选择 JSON，将下载的文件保存在仓库外。不要把私钥贴入聊天、提交到 Git 或输出到日志。

在 [仓库 Actions Secrets](https://github.com/Pixel-Tailor-CN/PixelText/settings/secrets/actions) 添加：

| Secret | 内容 |
| --- | --- |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | 下载的 JSON 文件完整原文，不做 Base64 编码 |
| `SIGN_KEY_STORE_BASE64` | 已有签名 keystore 的 Base64 内容 |
| `SIGN_KEY_STORE_PASSWORD` | 已有 keystore 密码 |
| `SIGN_KEY_ALIAS` | 已有密钥别名 |
| `SIGN_KEY_PASSWORD` | 已有密钥密码 |

也可在已登录 `gh` 的 PowerShell 中从本地文件写入，替换示例路径：

```powershell
Get-Content -Raw -LiteralPath 'C:\secure\pixeltext-publisher.json' | gh secret set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON --repo Pixel-Tailor-CN/PixelText
```

如果组织策略禁止生成服务账号密钥，不要绕过策略；改为配置 Workload Identity Federation，并调整工作流使用短期凭据文件。此工作流目前采用 JSON 密钥方式。

## 首次验收与维护

1. 确认服务账号权限、上传证书、Secret 均已配置。
2. 在 `main` 主分支推送包含 `ci` 的提交，查看 Actions 是否完成资源同步、Release AAB 构建及 Play 上传。
3. 在 Play Console 内部测试页面核对 versionCode 和发布状态，由名单内测试人员通过 Google Play 安装或升级。API 成功不等于测试设备已经收到更新，传播可能需要等待。

`versionCode` 继续由 `git rev-list HEAD --count` 生成，不能手工维护，也不改用 Actions 运行次数。新包必须使用未占用的版本号，并满足升级要求。同一提交重跑，或重写主分支历史后提交数不再递增，可能遇到重复或较低版本号。请保持主分支发布历史持续前进。

当前上传步骤不自动跳过已上传版本：若上传已经成功，重复上传会明确失败。若只是凭据校验或构建阶段失败，修复配置后可以重跑；若包已经上传但提交渠道失败，应先在 Play Console 确认状态，必要时复用已上传的包完成发布，不盲目重新上传。

上传步骤使用 `changesNotSentForReview: true`。内部测试轨道不需要审核，若省略该参数，Play 会以 `Changes cannot be sent for review automatically. Please set the query parameter changesNotSentForReview to true.` 拒绝提交，表现为“包已上传但发布失败”。使用该参数后编辑会直接提交到内部测试轨道，不自动送审；需要审核的变更在 Console 中手动送审。

`status: completed` 表示发布到内部测试，不会推广到生产。已有正式版本仍可能存在待处理编辑、审核或权限状态，须以实际 API 返回和 Console 状态为准。

常见失败：

- `missing required secret`：检查 GitHub Secret 名称和非空值。
- API 未启用或 `403`：确认 JSON 对应项目已启用 API、服务账号已获得此应用的测试发布权限。
- 包名不存在：确认授权的是正确应用和开发者账号。
- 签名不匹配：核对 Play 的上传证书；上传密钥不一定等于 Play 分发使用的应用签名密钥。
- 版本号已使用：按上述版本号与重跑规则处理。
- 编辑事务冲突：检查是否有人同时在 Console 修改发布，确认状态后再操作。

参考：[上传 Action](https://github.com/r0adkll/upload-google-play)、[GitHub 并发队列](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)、[Android 版本号](https://developer.android.com/studio/publish/versioning)。
