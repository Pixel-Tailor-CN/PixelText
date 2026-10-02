# Release 运行时回归

`release-runtime.yml` 使用公开仓库标准 `ubuntu-24.04` runner，无付费服务。构建原始 `release` 变体，保留 R8、资源压缩、生产包名、`arm64-v8a` 和所有原生库，只使用一次性 CI 签名。不修改主应用源码、清单、依赖、ABI 或 keep 规则，也不将应用编译成可调试版本。

API 35 Google APIs x86_64 官方镜像必须报告 ARM64 ABI 和原生转译桥才运行；独立 instrumentation 随后验证 APK 内 TensorFlow JNI 实际初始化。该证据是 Android ART/系统上的执行，不是真实 ARM 硬件性能或所有 Pixel 系统版本的证明。

测试包括：全新无权限安装、五次重复冷启动、默认短信系统弹窗取消返回、备份页面的枚举选项、最终 R8 DEX 中备份 adapter 的全部枚举 JSON 往返、明文/AES 备份容器导出与检查、vCard 2.1 QP 换行、3.0 电话/邮箱/照片、4.0 未知反射类型、原生库初始化和测试后冷启动。

旁路 probe APK 使用同一临时签名运行于被测应用进程，从最终 mapping 定位混淆成员；应用 APK 不重打包、不插桩、不增加保留规则。反射找不到目标或断言失败必须报错，不允许跳过。全部数据均为合成样本，证据含 JUnit、UI 截图/XML、设备属性、APK 哈希和 logcat。CI 密钥结束时删除，不上传。

边界：没有验证真实 SIM、运营商 SMS/MMS 收发、SAF 云端提供商、包含真实短信的恢复，或线上发布签名。容器测试使用应用私有缓存中的合成文件；不等同于完整用户备份恢复测试。

合并清理仅作用于 PR #17 的 `fix/r8-backup-vcard-reflection`：必须已合并、来自本仓库且当前分支 SHA 仍等于合并 head，才通过临时 GITHUB_TOKEN 删除此 refs/heads 引用并确认 404。不启用仓库全局自动删分支，不处理其他分支或标签。


## 单人 MMS 回调与编辑回归

现有默认关闭版先运行原冷启动、权限取消、备份、vCard和ARM64 JNI回归。之后以 `-Ppixeltext.enableMmsSending=true` 构建同一提交的受控混淆包，调用 `build_mms_probe.sh` 与 `mms_smoke.py`。两种APK和mapping分别保存，不能混用。

新增probe是独立、同临时签名的 instrumentation APK，不改变生产DEX、keep或应用权限，不添加app测试依赖。驱动拒绝真实设备，确认 `emulator-` serial、`ro.boot.qemu=1` 和ARM64转译；发送权限撤销且 `SEND_SMS` AppOps保持deny，probe内再次确认。所有合成请求直接从提交后状态开始且 `sourceId=null`，不会创建PREPARING/READY任务，也不调用SmsManager发送。测试完成后再次检查AppOps未出现访问/拒绝事件。

覆盖：真实mutable PendingIntent填入extra且不能覆盖固定身份→Manifest回调→独立Room→Worker、重复/未知/不同迟到回调、真实强停重启后迟到回调、提交栅栏恢复、删除后超时释放、有效MMSC拒绝与部分成功、异常Parcelable、纯文字SMS分流、多号码拒绝、正文转入彩信、旋转及删除草稿后保留SMS输入。

边界：这是离线结果处理和编辑UI验证，不测试真实SmsManager网络提交、运营商、计费、物理SIM、API31或实际MMS Provider持久化。Provider中断路径另有仓库外替身验证，生产入口仍以运营商验收为开启门槛。CI失败必须按具体原因调查，不能把默认版旧回归绿灯当成新MMS通过。
