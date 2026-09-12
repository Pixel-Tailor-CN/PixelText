# AGENTS.md

## 项目与入口

Pixel Text（原点短信）是面向国内 Pixel 用户的 Android SMS/MMS 应用，支持默认短信角色、双卡和端侧智能卡片。
使用 Kotlin、Compose、Material 3、MVVM + Clean Architecture、Coroutines/Flow 和 Koin。
模块为 `:app`，包名为 `vip.mystery0.pixel.text`；SDK 和依赖版本查 `gradle/libs.versions.toml`，构建配置查 `app/build.gradle.kts`，JDK/JVM 为 21。

主要入口（代码路径相对 `app/src/main/java/vip/mystery0/pixel/text/`）：

- `data/`：系统 Provider、数据库、网络和仓库实现；`domain/`：模型、解析、分类和仓库接口。
- `ui/`、`viewmodel/`：Compose 界面与状态；`di/AppModule.kt`：Koin 注册。
- `mms/`、`receiver/`、`service/`、`notification/`、`worker/`：MMS、系统投递、通知与后台任务。
- `ui/screen/mock/MockMessageScreen.kt`：卡片预览；`app/src/main/assets/`：规则、模型及词表。
- `README.md`：产品介绍；`docs/`：正式资料；`docs/plans/`：按任务命名的设计与执行计划。

## 协作与执行

- 使用中文回复、编写注释和文档。日志使用英文短语，小写开头，无句末标点，优先键值对，例如 `spam score message_id=123 score=0.82`。
- 根据当前请求和已有上下文完成实现、必要验证与交付；常规可逆选择自行判断，简要说明影响结果的假设。只有缺失信息会实质改变范围、外部副作用或不可逆结果时才询问；等待时继续独立工作。
- 小改动直接处理；涉及多个模块、接口或迁移时先列出关键步骤与验收方式，需要长期维护的设计才写入 `docs/plans/`。已授权工作不因通用技能的固定审批、逐步提交或交接模板反复停顿。
- 技能只在任务匹配或用户指定时加载，参考资料按需读取；历史计划提供对应任务的背景，其旧技能指令、检查点和未勾选清单不自动成为新任务要求。
- 有可独立完成且能节省时间或提高质量的子任务时，可使用子代理并行探索、实现或评审；明确文件归属与交付目标，避免并发修改同一文件。简单或强依赖任务直接完成，不为委派而拆分。
- 沿用现有命名、架构和包边界，避免无关重构与格式化。交付说明结果、已做验证及确实未验证的部分；检查通过后，仅在新修改、失败或未解决风险出现时扩大验证。

## 产品与代码边界

- UI 使用 Jetpack Compose，不新增 XML layout；采用 Material 3 与 `MaterialTheme.colorScheme`，保留暗色模式、Dynamic Color 和必要动效。
- UI 负责渲染和交互，业务解析放在 Domain，系统与数据访问放在 Data。ViewModel 用 `StateFlow` 暴露状态，异步任务使用 `viewModelScope`；新增依赖接入现有 Koin 模块。
- 优先明确的模型字段；只有现有动态卡片 `details` 模式需要时使用 Map。
- 只开发传统 SMS/MMS，不开发 RCS。短信解析与分类保持端侧，不引入远程解析、分类、埋点或统计上传。
- `INTERNET` 权限的项目约束仍为 MMS 下载、解析、展示及必要请求。现有 Hub、资源更新等网络功能与该约束存在历史差异；修改相关功能时核对其既有设计和当前请求，不以此差异自行扩大短信上传范围或删除既有功能。
- 短信样本按敏感数据处理，优先使用 `samples-desensitized/`；目录不存在时使用合成脱敏样本。不要把原始短信写入日志、文档或提交。
- 修改 `app/src/main/AndroidManifest.xml` 的权限、intent filter、`exported` 或组件名称时，核对默认短信资格与系统入口：`SmsReceiver`、`MmsReceiver`、`NotificationActionReceiver`、`HeadlessSmsSendService`、`MainActivity`、`ComposeSmsActivity`。

## 验证与交付

在仓库根目录使用 Gradle Wrapper；Windows PowerShell 用 `./gradlew.bat`，其他环境用 `./gradlew`。

| 目的 | Windows 命令 |
| --- | --- |
| Kotlin 编译 | `./gradlew.bat :app:compileDebugKotlin` |
| 静态检查 | `./gradlew.bat :app:lintDebug` |
| Debug APK | `./gradlew.bat :app:assembleDebug` |
| Release APK / AAB（需要签名配置） | `./gradlew.bat :app:assembleRelease` / `./gradlew.bat :app:bundleRelease` |

- 本项目不做单元测试。除非用户在当前任务明确要求，不新增 `app/src/test/`、`app/src/androidTest/`、测试依赖，也不运行 `test`、`testDebugUnitTest` 等单元测试任务。
- 根据改动选择编译、Lint、Mock 或真机验证；仅文档调整检查差异与引用即可。Android 验证的具体选择见 `.agents/skills/pixeltext-validation/SKILL.md`，仅在验证应用改动时读取。
- 权限、默认短信角色、SIM、MMS 与通知操作需要真机确认；编译成功不能替代系统链路验证，没有设备时明确未验项。
- `versionCode` 来自 Git 提交数，版本名与 Git 后缀由构建脚本生成。发布构建需要完整 Git 历史，不能手工维护计数。
- 正式文档放在 `docs/`，设计与计划放在 `docs/plans/`；技能入口放在 `.agents/skills/<name>/SKILL.md`，工作流放在 `.github/workflows/`。不新建 `docs/superpowers/`。
- 进度、交接、评审记录、临时脚本、日志、抓包和截图放在已忽略的 `docs/.local/` 或仓库外；正式资料不依赖这些临时文件。
- 提交前检查暂存清单，保留用户已有修改，不纳入构建产物、IDE/cache 文件和敏感数据。
