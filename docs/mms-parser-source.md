# 彩信解析器来源

`app/src/main/java/vip/mystery0/pixel/text/mms/vendor/` 包含 AOSP MMS 解析器及其数据类型，保留原始版权与 Apache 2.0 许可声明。它是应用内源码，不调用系统隐藏 API。

来源：https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/telephony/common/com/google/android/mms/

本地调整：替换包名，移除仅供平台构建的 UnsupportedAppUsage 注解；保留上游注释与许可文本。未引入 PduPersister/PduComposer，系统持久化通过本项目的公开 ContentResolver 实现。后续解析器修订需同步记录在此文档。

集成调整：上游日志重定向到无输出的 ParserLog，避免记录消息内容；开放原始头字段只读 getter 以完整写入 BCC 等信息；内容类型参数改为实例字段，避免不同解析任务相互污染；接收入口仍保留串行调用。保留原始下载 PDU 用于保真，系统消息删除后同步清理。

完整许可证见 [Apache 2.0](licenses/aosp-mms-Apache-2.0.txt)。健壮性调整：校验 part 声明长度与实际读取长度；uintvar 使用宽整数并验证范围和字节数；限制嵌套深度、累计 part 数及分配字节数。嵌套 multipart 保留原容器与全部子 part，不丢弃其他内容。

2026-09-07 multipart 调整：

- `PduPart.children` 保存真实层级；mixed、related、alternative 的 WAP MIME 与标准 MIME 名称均展开。Provider 按深度优先顺序保存容器载荷及叶子项，保留原 CID/Content-Location，不伪造关系列或标识。顶层 alternative 额外保存其原始 multipart 载荷作为容器 part。
- 所有递归层共用深度 8、累计 4096 part、64 MiB 预算，头字节也计费；失败恢复不返还预算。嵌套格式错误保留不透明原容器及外层有效兄弟项；累计预算超限使整个 PDU 解析失败，原下载文件仍由接收链路保留。
- part 头和 Content-Type 参数使用独立有界流，拒绝提前 EOF、越界声明长度、未终止字符串、非法递归 value-length。跳过字段不再按未验证长度分配数组。修复文本形式 Content-Transfer-Encoding 字段名首字符被吞的问题。
- 子项保持载荷顺序，不使用全消息 start/type 重排子项。容器不支持的传输编码保留原载荷，不猜测解码关系；普通叶子项仍沿用原 base64/quoted-printable 解码。
- 内容仓库读取本地容器后，按唯一 CID/Content-Location 匹配 Provider 子项；重复、缺失或循环关系回退全部叶子。alternative 只影响正文选择，所有原件均留在 `MmsContentModel.parts`。
- SMIL 由独立本地解析器生成顺序页，拒绝 DOCTYPE/实体声明，限制 UTF-8 1 MiB、深度 32、128 页；非法结构回退叶子附件。SMIL 和内部容器永远不加入通用正文与搜索文本。
