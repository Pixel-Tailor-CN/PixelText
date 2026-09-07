# 彩信名片依赖

- `com.googlecode.ez-vcard:ez-vcard:0.12.2`：FreeBSD（BSD 2-Clause），[官方 POM](https://central.sonatype.com/artifact/com.googlecode.ez-vcard/ez-vcard/0.12.2)。完整许可见 `ez-vcard-ezvcard-ez-vcard.license`；库内移植的 Commons Codec 许可见 `ez-vcard-ezvcard-commons-codec.license`。
- Gradle 实际传递依赖：`vinnie:2.0.2`、`jsoup:1.23.2`、`freemarker:2.3.34`、`jackson-core:2.21.0`。`jackson-databind` 为上游 optional，未进入本应用运行时。相关许可从实际二进制 JAR 中提取到本目录的 `vinnie-*`、`freemarker-*`、`jackson-core-*` 文件；jsoup 复用已有 MIT 许可。
- 只调用 `VCardReader(String)`、`readNext()` 及本地属性读取 API；未使用库的 HTML、XML、JSON 或写入入口。检查源码 `VCardReader.java`、`BinaryPropertyScribe.java`、`ScribeIndex.java`，确认文本 PHOTO 只生成字节或 URL 字符串，不自行下载。应用仅保留有界图片字节和消息内 CID/Content-Location，远程地址不会进入 Coil。
- 保留上游全部声明依赖，不以排除依赖或 `dontwarn` 隐藏兼容性问题。升级依赖时应验证 Debug 编译、Lint、Release R8，以及最低 API 设备和 Release APK 运行兼容性。
