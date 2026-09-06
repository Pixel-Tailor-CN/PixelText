# 彩信解析器来源

`app/src/main/java/vip/mystery0/pixel/text/mms/vendor/` 包含 AOSP MMS 解析器及其数据类型，保留原始版权与 Apache 2.0 许可声明。它是应用内源码，不调用系统隐藏 API。

来源：https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/telephony/common/com/google/android/mms/

本地调整：替换包名，移除仅供平台构建的 UnsupportedAppUsage 注解；保留上游注释与许可文本。未引入 PduPersister/PduComposer，系统持久化通过本项目的公开 ContentResolver 实现。后续解析器修订需同步记录在此文档。

集成调整：上游日志重定向到无输出的 ParserLog，避免记录消息内容；开放原始头字段只读 getter 以完整写入 BCC 等信息；每次构造重置上游共享内容类型参数，且入口串行调用。保留原始下载 PDU 用于保真，系统消息删除后同步清理。

完整许可证见 [Apache 2.0](licenses/aosp-mms-Apache-2.0.txt)。健壮性调整：校验 part 声明长度与实际读取长度；uintvar 使用宽整数并验证范围和字节数；限制嵌套深度、累计 part 数及分配字节数。嵌套 multipart 保留原容器与全部子 part，不丢弃其他内容。
