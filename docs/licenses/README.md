# 彩信第三方来源与许可

本目录保存彩信解析、HTML 展示和名片读取所涉及的第三方许可原文与来源索引，不是全应用依赖清单。中文说明用于维护；英文 `LICENSE`、`NOTICE` 和版权声明保持上游原文，不翻译或合并其中的第三方声明。

## 源码引入

应用内的 [MMS vendor 目录](../../app/src/main/java/vip/mystery0/pixel/text/mms/vendor/)包含 AOSP MMS 解析器及其数据类型。许可全文见 [AOSP Apache-2.0](aosp-mms-Apache-2.0.txt)，源码文件中的原始版权与许可头也需要保留。

### 上游基线

- 项目：Android Open Source Project，`platform/frameworks/base`。
- 固定提交：`1cdfff555f4a21f71ccc978290e2e212e2f8b168`。
- [上游源码目录](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/telephony/common/com/google/android/mms/)：`telephony/common/com/google/android/mms/`。
- 引入范围：PDU 解析器、消息头、消息体及相关数据类型；未引入 `PduPersister`、`PduComposer`。应用直接编译这些源码，不调用系统隐藏 API；Provider 持久化由项目自己的 `ContentResolver` 实现负责。

### 本地修改摘要

- 替换包名，移除平台专用的 `UnsupportedAppUsage` 注解；上游日志调用重定向到无输出的 `ParserLog`，避免记录消息内容。
- 开放原始头字段只读 getter，保留 BCC 等字段；暴露原始 Expiry 数值与相对时间标记，供接收层确定截止时间。
- 将内容类型参数改为实例字段，避免不同解析任务相互污染。
- 加固声明长度、实际读取长度、uintvar、字符串终止和字段边界校验；递归层共用深度、累计 part 数和字节预算。
- `PduPart.children` 保留 multipart 层级及子项载荷顺序，支持 mixed、related、alternative 的 WAP MIME 与标准 MIME 名称；保存原容器及全部子 part，不按全消息 start/type 重排子项。
- 嵌套格式或解码错误保留不透明容器和外层有效兄弟项；预算超限使整个 PDU 解析失败。容器遇到不支持的传输编码时保留原载荷，不猜测解码关系。

后续修改 vendor 源码时，应保留原始声明，并在本节同步有助于回溯的差异；接收流程与内容展示的行为以对应实现为准。

## 二进制依赖

以下版本于 2026-09-12 对照 `debugRuntimeClasspath` 核对；直接依赖版本以[版本目录](../../gradle/libs.versions.toml)为准。许可文件从对应版本的 Gradle 缓存 JAR/AAR 中提取，并按字节核对。

| 组件 | 引入关系与用途 | 许可原文 |
| --- | --- | --- |
| `org.jsoup:jsoup:1.23.2` | 直接依赖；本地 HTML DOM 解析，也是 ez-vcard 的传递依赖 | [MIT](jsoup-MIT.txt) |
| `androidx.webkit:webkit:1.17.0` | 直接依赖；系统 WebView 与 WebViewAssetLoader | [Apache-2.0](webkit-Apache-2.0.txt) |
| `com.googlecode.ez-vcard:ez-vcard:0.12.2` | 直接依赖；本地 vCard 读取 | [BSD 2-Clause](ez-vcard-ezvcard-ez-vcard.license)、[内嵌 Commons Codec 的 Apache-2.0](ez-vcard-ezvcard-commons-codec.license) |
| `com.github.mangstadt:vinnie:2.0.2` | ez-vcard 传递依赖 | [MIT 与内嵌 Commons Codec 的 Apache-2.0](vinnie-LICENSES) |
| `org.freemarker:freemarker:2.3.34` | ez-vcard 传递依赖 | [Apache-2.0](freemarker-META-INF-LICENSE) |
| `com.fasterxml.jackson.core:jackson-core:2.21.0` | ez-vcard 传递依赖 | [LICENSE](jackson-core-META-INF-LICENSE)、[NOTICE](jackson-core-META-INF-NOTICE)、[FastDoubleParser LICENSE](jackson-core-META-INF-FastDoubleParser-LICENSE)、[FastDoubleParser NOTICE](jackson-core-META-INF-FastDoubleParser-NOTICE)、[第三方 LICENSE](jackson-core-META-INF-thirdparty-LICENSE) |

ez-vcard 请求的 jsoup `1.22.1` 被应用直接声明的 `1.23.2` 替代。`jackson-databind` 在上游 POM 中是 optional，未进入本次核对的运行时依赖。Commons Codec 代码内嵌在相关库中，不是单独解析出的运行时模块；其许可仍保留。没有因为当前仅调用 vCard 文本读取入口而移除传递依赖或配套声明。

## 原文在归档中的位置

| 本地文件 | JAR/AAR 内原始路径 |
| --- | --- |
| `jsoup-MIT.txt` | `META-INF/jsoup/LICENSE` |
| `webkit-Apache-2.0.txt` | `META-INF/androidx/webkit/webkit/LICENSE.txt` |
| `ez-vcard-ezvcard-*.license` | `ezvcard/*.license` |
| `vinnie-LICENSES` | `LICENSES` |
| `freemarker-META-INF-LICENSE` | `META-INF/LICENSE` |
| `jackson-core-META-INF-*` | `META-INF/` 下的同名原文 |

文件名保留组件前缀和原始条目名，便于升级时逐一核对。多个文件即使具有相同的 Apache-2.0 正文，也分别保留其组件对应关系；`NOTICE`、版权声明和复合许可证不能仅因许可证类型相同而删除。

## 维护方式

升级这些依赖时，在仓库根目录运行 `./gradlew.bat :app:dependencies --configuration debugRuntimeClasspath`，核对实际解析版本及传递依赖，再检查对应 JAR/AAR 中的全部许可与通知条目。同步更新本索引、原文和源码引入记录；实现细节以代码为准，设计历史留在 `docs/plans/`。

本目录继续作为原文来源与溯源索引。应用现通过 AboutLibraries 在设置 → 关于 → 开源声明中展示依赖；本页列出的许可原文由构建任务打包到相应组件的声明中。补充配置、离线阅读与发布核对方式见[开源声明维护](../development/open-source-notices.md)。
