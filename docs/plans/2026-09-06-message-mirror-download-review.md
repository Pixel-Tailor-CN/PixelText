# 彩信下载链路代码审查

审查日期：2026-09-06。本文已按第二轮及最终追加修正更新。依据 `2026-09-06-message-mirror-design.md` 的下载要求，只复核原审查范围及对应修正；未运行 Gradle、单元测试或真机测试。以下行号对应第二轮读取的工作区。

## 原问题复核状态

| 原问题 | 第二轮结论 | 代码证据 |
| --- | --- | --- |
| 重复 WAP Push 无条件插入 | 已修；接收器在插入前按事务、内容位置、接收 SIM 查询，重复时直接返回 | `receiver/MmsReceiver.kt:58–69` |
| part 长度可导致巨大分配/截断补零 | 已修；分配前核对剩余输入、累计 64 MiB、4096 part、8 层嵌套，并检查实际读取数；uintvar 使用 long 累积、最多 5 字节且验证 Int 范围和结束位 | `mms/vendor/pdu/PduParser.java:844–863`、`:920` |
| 嵌套 alternative 只保留首项 | 已修；保留容器原始数据并追加全部子项 | `mms/vendor/pdu/PduParser.java:921–931` |
| Provider 失败删除成功下载 PDU | 已修；`save_failed` 保留文件并允许后台和手动本地重试，超时恢复先验证现有 PDU | `mms/MmsDownloadCoordinator.kt:41–44`、`:119–132`、`:166–170` |
| 失败丢失占位 FROM | 已修；旧地址 ID 在写入前持久化，完成头之后恢复会继续清理旧地址，清理异常保持 save_failed，已提交的新内容不回滚 | `mms/MmsProviderWriter.kt:32–43`、`:119–128`，`mms/MmsDownloadCoordinator.kt:157–162` |
| 镜像读取未提交 part | 已修；Provider 写入与元数据同步使用同一 `MirrorSynchronizationLock` | `mms/MmsDownloadCoordinator.kt:156–158`、`data/repository/mirror/MessageMirrorSynchronizer.kt:37` |

迟到回调复核：`MmsDownloadCoordinator.kt:98–99` 同时核验令牌和 `downloading` 阶段。旧令牌、已恢复完成或已进入保存阶段的回调不会再次修改请求。成功 PDU 保留在私有目录、完成后撤销 URI 授权；源消息不存在时由恢复清理。以上为静态代码结论，未宣称已通过进程死亡或运营商真机验证。

第二轮追加修正已复核：完成标记后的旧地址清理现已纳入持久日志，原先提出的重复 FROM 恢复问题已解决。

最终追加修正已复核：`mms/vendor/pdu/PduParser.java:992–1001` 使用 `long` 累积，最多读取 5 字节，每次转换前检查 `Integer.MAX_VALUE`，输入提前结束或第五字节后仍有延续位均返回失败。原 uintvar 溢出问题已解决。

## 尚未解决的问题

本次限定审查范围内，没有剩余可定位的 P0/P1/P2 问题。原审查发现均已在当前实现中修正。

## 验证边界

本轮只做静态路径复核，没有新增业务代码或测试。默认关闭自动下载、手动下载不受开关限制、接收 SIM 选择、FileProvider 精确 URI 授权和备份排除等上一轮已确认项没有发现新的具体问题。
