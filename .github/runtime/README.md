# Release 混淆后运行检测

`release-runtime.yml` 在 PR 打开、更新、重新打开或手动触发时，构建启用 R8 和资源压缩的 Release APK，仅验证以下内容：

- vCard 2.1：Quoted-Printable 编码及折行解析。
- vCard 3.0：电话、邮箱、照片解析。
- vCard 4.0：未知扩展类型的反射解析。
- APK 中 ARM64 TensorFlow Lite 原生库的实际初始化。

## 执行方式

使用 `ubuntu-24.04` runner 和 Android 15（API 35）Google APIs x86_64 模拟器，要求镜像支持 ARM64 转译。应用保留正式包名、ABI、原生库和正常 Release 配置，仅使用一次性 CI 签名。

`prepare_probe.py` 从最终 R8 mapping 提取 vCard 解析与原生库初始化所需的混淆类名和成员名。`build_probe.sh` 将 `RuntimeProbe.java` 编译为独立、同临时签名的 instrumentation APK，在被测应用进程内调用最终混淆代码，不修改应用 DEX、keep 规则或生产入口。

`runtime_smoke.py` 仅安装应用与检测 APK、执行检测并校验结果，不操作界面或验证短信权限。找不到反射目标、解析结果不符或原生库初始化失败时，工作流失败。

## 产物与边界

检查使用合成联系人数据，保留 instrumentation 输出、JUnit 结果、构建日志、设备信息、崩溃日志、APK 哈希、APK 和 R8 mapping，Actions 产物保留 7 天；临时签名密钥在上传产物前删除。

此检测验证混淆后 vCard 解析和 TensorFlow Lite JNI 初始化，不覆盖备份、冷启动、界面、SMS/MMS 收发、运营商或正式发布签名，也不代表真实 ARM 硬件性能与全部 Pixel 系统版本均已验证。
