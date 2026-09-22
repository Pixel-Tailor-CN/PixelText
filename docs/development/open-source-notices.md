# 开源声明维护

## 应用入口

设置 → 关于 → 开源声明。使用 AboutLibraries 15.2.0 的 Android Gradle 插件与 Compose Material 3 组件，随应用主题显示组件名称、版本、作者和许可证。展开条目可查看许可或打开项目链接。

“查看许可”读取 APK 中的原文，不打开许可网页；只有源码/项目主页操作交给外部浏览器。许可正文保留英文、换行、版权和 NOTICE，支持滚动与文字选择，不进行 HTML 标签清理。没有可处理链接的应用或 URL 不受支持时显示提示，不使页面崩溃。

## 构建与数据来源

- 依赖版本集中在 `gradle/libs.versions.toml`；插件在构建时按变体生成 `app/build/generated/aboutLibraries/<variant>/res/raw/aboutlibraries.json`。
- 清单包含直接和传递依赖。插件默认合并同一 Kotlin Multiplatform 项目的根模块及 Android/JVM 发布物，排除 platform/BOM 条目，不加入测试依赖或 Gradle 插件自身的构建依赖。
- 元数据来自依赖 POM，标准许可正文由插件在构建期获取 SPDX 数据。未启用 GitHub 远程许可/赞助查询；首次构建可能需要联网，应用内阅读不需要联网。
- `app/src/main/res/raw/open_source_keep.xml` 防止 Release 资源压缩移除清单；不依赖 Debug 中的资源名称推断 Release 路径。

## 人工补充

`config/open-source/notices.json` 记录 Gradle 无法自动识别的源码，以及现有组件的补充声明：

- AOSP MMS 解析器单独建项，保留固定提交链接、源码版权和本地修改说明。
- RE2/J、jsoup、WebKit、ez-vcard、vinnie、FreeMarker、Jackson Core、Zip4j 对应仓库中的原始许可文件。
- ez-vcard/vinnie 内嵌 Commons Codec，以及 Jackson 内嵌 FastDoubleParser 等声明仍保留在其所属组件内，不误报为独立 Gradle 依赖。

`generateOpenSourceNotices` 从上述索引读取原文，生成插件所需的 `libraries/` 与 `licenses/` JSON 到 `app/build/generated/openSourceNotices/`。这些构建产物不提交；源文件仍以 `docs/licenses/` 和既有 RE2/J asset 为准，不手工维护第二份转义后的正文。

`config/open-source/license-overrides.json` 补充无法映射到 SPDX 的 POM 许可：当前为 RE2/J 的 Go License 和 ez-vcard 的 FreeBSD License。`hash` 是当前插件生成的许可 ID，不是原文文件的校验和；升级插件/POM 后需重新核对，不能假定永远不变。

新增源码时补充 `uniqueId`、名称、来源、固定版本/提交、作者、版权与 `noticeFiles`。新许可原文放在 `docs/licenses/`，不要覆盖源码中的原始许可头。新增其他位置的原文时，也应更新生成任务的输入声明，以确保增量构建重新生成。现有组件的补充项以 Maven `group:artifact` 为 `uniqueId`，由插件合并元数据，不重复创建组件。

## 验证与升级

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleRelease
./gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
```

升级依赖、插件或修改补充索引后：

1. 对照实际运行时依赖核对生成清单；识别多平台发布物合并，检查版本是否匹配。
2. 检查每个组件都有许可证，且每个引用的许可证都有非空正文。非标准名称不能仅保留网页链接而宣称可离线阅读。
3. 核对补充原文完整存在于对应组件的许可内容中，特别是复合许可、版权和 NOTICE。
4. 检查 Release APK 中仍存在与生成结果一致的 JSON。R8/资源优化可能改变路径，不能仅查找 `res/raw/aboutlibraries.json`。
5. 在设备上断网打开标准许可与源码补充声明，检查长文滚动、关闭、返回和深色模式。

AboutLibraries 无法自动识别未在依赖元数据中声明的内嵌代码、原生子依赖或手工复制源码。应结合上游分发文件和仓库来源记录维护人工补充；自动清单不替代这部分核查。

本轮 API 37 模拟器验证了离线正文、原始源码版权、长文滚动、关闭/返回与深色模式；Debug 和经 R8/资源压缩的 Release 包均保留声明资源。未在实体机或 API 31 验证此页面。
