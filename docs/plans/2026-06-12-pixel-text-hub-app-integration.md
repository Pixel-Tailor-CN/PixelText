# Pixel Text Hub 客户端集成

## 当前范围

客户端已经接入用户确认后的样本上报、上报记录查询及手动规则／模型更新。短信解析和分类仍在端侧执行；打开编辑页、辅助脱敏或本地学习不会自动上传短信。资源自动检查的独立边界见[自动检查计划](2026-06-26-resource-auto-update-check-implementation-plan.md)。

本文件原有的逐步代码模板属于首次接入方案，已被以下实现替代，不再作为重新实现或逐步提交的操作清单。

## 旧方案与当前实现

| 首次方案 | 当前实现与源码依据 |
| --- | --- |
| `BuildConfig.PIXEL_TEXT_HUB_BASE_URL`、Gradle 属性和示例域名 | `di/AppModule.kt` 注册 `PixelTextHubClient` 时提供服务地址；当前构建脚本没有该 BuildConfig 字段 |
| `HttpURLConnection`、手写 JSON 的 v1 客户端 | `data/source/PixelTextHubClient.kt` 使用 Retrofit／Moshi；资源清单为 `api/v2/resources/manifest`，上报为 `api/v2/sample-reports` 及其 list/detail 路由 |
| `SampleSubmissionRequest`、一次请求后仅返回成功／失败 | `domain/hub/SampleReportModels.kt` 与 `SampleReportDtos.kt` 提供 v2 协议；安装身份、请求 UUID 和确认负载由上报仓库持久化，未知结果重试使用原请求 |
| 仅按模型／词表版本和 SHA-256 判断可用性 | `HubModels.kt` 的模型资源还包含契约文件、最低应用版本、预处理版本、个人参数版本和特征兼容标识；不兼容模型明确拒绝 |
| 先删除旧文件，再分别移动模型和词表 | `HubResourceStore.kt` 将模型、词表、契约作为同一不可变目录，通过 `AtomicFile` 切换活动指针；规则文件也采用原子写入 |
| 分类器独立读取模型和词表 | `SpamClassifier.kt` 通过模型引擎使用完整资源组合；`PersonalLearningCoordinator` 协调资源激活及个人参数，避免混合版本 |
| 设置页直接进入单页贡献表单 | 当前设置“骚扰识别与拦截”提供“我的上报”，贡献页面分为内容编辑与确认提交；会话和个人学习入口复用同一流程 |
| `mutableStateOf` 分散维护表单，初始类别为验证码 | `SampleSubmissionViewModel` 通过 `StateFlow<SampleSubmissionState>` 暴露状态，保留草稿、待确认请求及手动辅助脱敏状态 |

代码路径除构建脚本外均相对 `app/src/main/java/vip/mystery0/pixel/text/`。

## 资源更新

`HubResourceRepository.checkResourceUpdateAvailability()` 比较当前规则版本与活动模型组合版本。清单缺失某类资源时保留该类当前版本；两类均不存在时显示没有兼容资源，不将缺项误判为回退到内置版本。

用户确认安装后，`updateAll(manifest, onProgress)` 串行执行下载、字节数和 SHA-256 校验。规则先解析，再原子写入并重新建立验证码索引。模型下载包含模型、词表、契约三部分，候选校验通过后由学习协调器激活。资源更新和初始化通过 `DataInitializationGuard` 协调；个人学习开启且有样本时，候选资源需完成相应个人参数准备。

规则位于私有 `hub_resources/rules/rules.json`。模型组合位于 `noBackupFilesDir/spam_bundles_v2`；无有效活动组合时加载通过契约校验的内置组合。当前内置组合标识为 `builtin-v2`，不能沿用“首次所有版本都显示 builtin”的旧验收假设。

资源元数据继续由 `AppSettingsRepository` 和 SharedPreferences 保存；设置页通过 `SettingsViewModel` 展示检查、可更新、下载进度、成功与失败状态。取消协程会向上传播，不伪装成普通更新成功。

## 样本贡献与隐私

`SampleSubmissionRepository` 管理编辑草稿、确认后的不可变请求和实际上传；`SampleSubmissionViewModel` 管理界面状态。草稿初始保留正文与可选发送方，用户可手动编辑或主动应用本地辅助脱敏结果。修改草稿后需重新确认；本地学习正文不被上传编辑覆盖。

确认页面展示实际提交的正文、发送方、类别及版本、身份和可选学习反馈。只有用户明确确认后才提交；取消不上传，未知结果仅由用户主动重试。`ANDROID_ID` 随确认负载发送用于必要的反滥用和记录查询。当前公开说明以 [PRIVACY.md](../../PRIVACY.md) 为准。

后续协议与界面细节见[我的上报实施计划](2026-10-29-my-reports-implementation-plan.md)及[端侧学习设计](2026-11-01-on-device-learning-design.md)。不恢复旧 v1 接口、单独词表激活或自动上传流程。

## 验证边界

本次仅通过源码核对更新文档，没有新增运行验证。首次计划的编译、Lint、设备冒烟步骤未记录通过结果，不能据此宣称已完成。维护相关代码时按改动运行仓库根目录的 `./gradlew.bat :app:compileDebugKotlin` 和必要的 `:app:lintDebug`，不新增或运行单元测试。

仍需按具体变更核对：未同意时提交禁用、草稿恢复、确认负载冻结、未知结果重试、不兼容清单、下载中断、校验失败以及模型组合切换失败。网络验证使用授权的模拟端点与合成脱敏内容，不向真实服务发送测试短信。
