# 彩信 HTML 依赖

- `org.jsoup:jsoup:1.23.2`，MIT。[官方发布说明](https://jsoup.org/news/release-1.23.2)、[官方许可证](https://jsoup.org/license)。完整许可证从实际解析的 JAR `META-INF/jsoup/LICENSE` 提取到 [jsoup-MIT.txt](jsoup-MIT.txt)。仅调用内存 HTML DOM 解析，不调用网络连接 API。
- `androidx.webkit:webkit:1.17.0`，Apache-2.0。版本由 [Google Maven 元数据](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/maven-metadata.xml) 核实并经 Gradle 解析；[AndroidX 发布页](https://developer.android.com/jetpack/androidx/releases/webkit)。使用系统 WebView 与 WebViewAssetLoader，不打包浏览器内核。Apache-2.0 全文复用 [aosp-mms-Apache-2.0.txt](aosp-mms-Apache-2.0.txt)。

解析前限制 256 Ki UTF-16 字符，并使用 [Parser.setMaxDepth](https://jsoup.org/apidocs/org/jsoup/parser/Parser#setMaxDepth(int)) 在建树期间限制 64 层开放元素。达到该深度由 jsoup 移除新增开放元素，不执行外部实体解析。
