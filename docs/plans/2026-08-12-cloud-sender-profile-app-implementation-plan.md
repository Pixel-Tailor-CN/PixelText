# 云端发件方资料 App 端实施计划

## 当前实现核对（2026-11-07）

以下以当前 Android 源码为准；服务端响应示例是协议约定，不代表本轮访问或验证了服务端。原设计中的损坏版本自动回退、资料变更自动刷新和更新时间展示尚未形成完整实现，不作为已交付能力。

- `data/db/ConversationCacheDatabase.kt` 当前为 v3，保留资料 generation/state/profile/number 表；消息列表已由 `MessageMirrorDatabase` v7 的 `MirrorConversationQuery` 聚合，`MessageRepositoryImpl.enrichWithSenderProfiles()` 再批量查询资料并补充联系人名称。
- 当前没有订阅资料表变化来触发上述列表投影；安装成功会更新设置页版本状态，列表在下一次查询时读取新资料。旧 `cached_conversation` 联表 DAO 仍在代码中，但不再是主列表数据源。
- `SenderProfileRepository` 保留 active/previous 两代，但没有启动校验或自动切换 previous 的路径。头像解码失败由 `SenderAvatar` 回退默认图标；不能据此声称整个损坏版本自动恢复。
- `SenderProfileStore` 返回并入库的是私有头像**绝对路径**。ZIP 内引用仍为相对路径，号码逐字精确匹配，无全量常驻 Map。
- 通知在已有异步链路中等待本地 `findByNumber()` 查询；没有独立的资料预加载就绪状态，也不会为资料更新重建已发通知。

源码入口均相对 `app/src/main/java/vip/mystery0/pixel/text/`。历史验收清单只表示验证要求，本轮仅做文档与源码核对。

> 执行本计划时，以当前用户请求和根目录 AGENTS.md 为准；按任务需要选择技能与验证方式。

**Goal:** 在 PixelText 中加入可手动安装的发件方资料，使用同一 Room 数据库联表匹配号码，在会话名称、头像、详情标题和通知中展示云端身份。

**Architecture:** 服务端 CDN ZIP 经过校验与导入管线，头像写入版本化私有文件，标签和号码写入 `ConversationCacheDatabase`。数据库使用单行 state 原子切换 active/previous generation，当前会话由镜像库聚合，再批量查询资料补充名称与头像，不刷写 `cached_conversation`；主列表尚未订阅资料更新。

**Tech Stack:** Kotlin、Room、Coroutines/Flow、Jetpack Compose、Koin、Moshi、Android Bitmap/WebP

## Global Constraints

- 第一版只有设置页手动安装，不创建自动更新 Worker。
- 号码按原始字符串精确匹配，不做任何规范化。
- 联系人名称 > 云端名称 > 原始号码；云端头像 > 默认头像。
- 头像存应用私有文件，Room 当前保存私有头像绝对路径和 SHA-256。
- 不新增单元测试，验证使用编译、Lint、模拟器和数据库检查。

---

### Task 1: 协议模型

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/model/SenderProfileModels.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/model/ConversationModel.kt`

- [ ] 增加 manifest、bundle、安装状态和查询结果模型。
- [ ] 为 `ConversationModel` 增加动态 `avatarPath/avatarSha256` 字段。
- [ ] 编译验证。

### Task 2: Room Schema、Migration 和联表 DAO

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/db/ConversationCacheDatabase.kt`

- [ ] 增加 generation、state、profile、number Entity 和外键。
- [ ] 增加 `CachedConversationWithSenderProfile` 联表结果。
- [ ] 增加主列表 Flow、批量 thread 查询、单号码查询和导入/清理 DAO。
- [ ] 数据库升级并增加显式 Migration。
- [ ] 编译验证。

### Task 3: ZIP 校验、头像文件和事务导入

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/resource/SenderProfileStore.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/SenderProfileRepository.kt`

- [ ] 实现受限 ZIP 读取、路径安全、JSON Schema v2、号码重复、头像存在/大小/哈希/BitmapFactory 可解码校验（路径限制为 .webp，不额外识别实际编码）。
- [ ] 将头像复制到版本目录。
- [ ] 在 Room Transaction 中导入 generation/profile/number 并切换 state。
- [ ] 导入失败清理无引用目录，成功后清理旧 generation。
- [ ] 提供线上 manifest 检查、CDN 安装、当前版本和按号码查询。
- [ ] 编译验证。

### Task 4: 会话 Repository 联表接入

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/ConversationCacheRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`

- [ ] 原定主列表长期联表 Flow 已被镜像聚合 + 批量资料查询替代；资料安装自动触发主列表刷新仍未实现。
- [ ] 当前主列表、归档和骚扰列表批量补充云端资料；搜索结果只补充 SIM 和骚扰判定，不展示云端头像。
- [ ] 联系人名称优先，云端头像独立保留。
- [ ] 不触发 fullSync 或改写缓存表。
- [ ] 编译验证。

### Task 5: Compose 本地头像和列表 UI

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/component/SenderAvatar.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/ConversationListScreen.kt`

- [ ] 在 IO 线程解码本地 WebP，并按路径与哈希缓存。
- [ ] 会话列表命中时显示云端头像，失败回退默认头像。
- [ ] 保留未读角标和选择状态。
- [ ] 编译验证。

### Task 6: 详情标题和通知

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/ConversationDetailViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/notification/SmsNotificationHelper.kt`
- Modify: relevant SMS/MMS notification call sites

- [ ] 详情标题按联系人、云端、原始号码优先级解析。
- [ ] 通知在异步链路内等待本地资料查询并使用名称/头像；无匹配时回退，不触发网络。
- [ ] 回复和 Intent 始终保留原始号码。
- [ ] 编译验证。

### Task 7: 设置页独立手动更新

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`

- [ ] 增加独立 SenderProfileUpdateState。
- [ ] 设置页增加独立“发件方资料”分类、版本和手动检查入口。
- [ ] 线上检查显示可安装版本，用户确认后安装并显示进度/结果。
- [ ] 注册 Store、Repository 和依赖注入。
- [ ] 编译验证。

### Task 8: 完整验证

- [ ] 运行 `./gradlew :app:compileDebugKotlin`。
- [ ] 运行 `./gradlew :app:lintDebug`。
- [ ] 运行 `./gradlew assembleDebug` 并安装模拟器。
- [ ] 在模拟器设置页安装线上发件方资料。
- [ ] 检查 Room active generation 和号码数据。
- [ ] 验证匹配号码的会话名称和头像；验证详情标题。
- [ ] 检查 logcat 无崩溃和发件方资料错误。
- [ ] 修复发现的问题并重复验证。
