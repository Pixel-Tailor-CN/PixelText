# 云端发件方资料 App 端实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PixelText 中加入可手动安装的发件方资料，使用同一 Room 数据库联表匹配号码，在会话名称、头像、详情标题和通知中展示云端身份。

**Architecture:** 服务端 CDN ZIP 经过校验与导入管线，头像写入版本化私有文件，标签和号码写入 `ConversationCacheDatabase`。数据库使用单行 state 原子切换 active/previous generation，会话 Flow 通过 SQL 联表自动刷新，不刷写 `cached_conversation`。

**Tech Stack:** Kotlin、Room、Coroutines/Flow、Jetpack Compose、Koin、Moshi、Android Bitmap/WebP

## Global Constraints

- 第一版只有设置页手动安装，不创建自动更新 Worker。
- 号码按原始字符串精确匹配，不做任何规范化。
- 联系人名称 > 云端名称 > 原始号码；云端头像 > 默认头像。
- 头像存应用私有文件，Room 只保存相对路径和 SHA-256。
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

- [ ] 实现受限 ZIP 读取、路径安全、JSON Schema v2、号码重复、头像存在/大小/哈希/WebP 解码校验。
- [ ] 将头像复制到版本目录。
- [ ] 在 Room Transaction 中导入 generation/profile/number 并切换 state。
- [ ] 导入失败清理无引用目录，成功后清理旧 generation。
- [ ] 提供线上 manifest 检查、CDN 安装、当前版本和按号码查询。
- [ ] 编译验证。

### Task 4: 会话 Repository 联表接入

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/ConversationCacheRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/MessageRepositoryImpl.kt`

- [ ] 主列表改用长期联表 Flow。
- [ ] 归档、骚扰和搜索结果批量补充云端资料。
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
- [ ] 通知使用云端名称和 LargeIcon；资料不可用时无阻塞回退。
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
