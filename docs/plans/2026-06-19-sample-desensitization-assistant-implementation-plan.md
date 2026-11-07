# 样本辅助脱敏实现与维护计划

## 当前实现

辅助脱敏已接入 `SampleSubmissionScreen`，本地领域逻辑位于 `domain/sample/`。原计划中的“其他”类型、返回裸字符串的替换接口、分散的 `mutableStateOf` 表单状态和片段 `FilterChip` 模板已不适用，当前接口如下。

| 文件或接口 | 当前职责 |
| --- | --- |
| `SensitiveType.kt` | 姓名、手机号、身份证号、地址、银行卡号、订单号、验证码、金额、日期、时间、日期时间；没有“其他”类型 |
| `SampleTextTokenizer.tokenize()` / `selectionUnits()` | 保留原始偏移；中文分为单字选择单元，数字及字母数字成组，分隔符保留 |
| `FakeSampleGenerator` | 生成虚拟姓名、号码、地址、银行卡号及保持字符形态的订单号／验证码；身份证包含校验位 |
| `SampleDesensitizer.replace()` | 返回 `SampleReplacementResult.Success(content)` 或 `Failure(error)`；非法选区返回原文的 Success，金额／时间格式不支持时返回明确错误 |
| `TemporalFormatPreservingGenerator` | 对日期、时间、日期时间按支持的输入格式生成替换值 |
| `SampleDesensitizationAssistant.replaceSelected()` | 返回 `ReplaceSelectedResult`，同时携带工作状态和可选错误；成功后重新分片、清空选区 |
| `SampleSubmissionViewModel.state` | `StateFlow<SampleSubmissionState>`，辅助状态位于 `desensitizationState`，不再暴露独立可变表单属性 |

文件路径均相对 `app/src/main/java/vip/mystery0/pixel/text/`。

## 交互与边界

1. 在内容编辑步骤点击“辅助脱敏”，以当前正文创建本地工作副本；内容为空或页面忙碌时不可进入。
2. 用户选择连续片段与敏感类型，主动替换。点击已选范围内的片段可重新缩小选区；替换只影响选区。
3. 面板保留类型选择、工作文本及错误提示。当前分片布局由 `DesensitizationAssistantSheet` 和片段组件负责，不再使用旧计划中逐片 `FilterChip` 的示例。
4. `applyDesensitizedDraft()` 经 `updateContent()` 回写草稿并撤销旧同意，再关闭面板；放弃未应用修改不会改动原草稿。
5. `requestCloseDesensitizationAssistant(): Boolean` 判断是否需要放弃确认；未应用修改仍需用户选择继续编辑或放弃。

该工具没有自动脱敏开关，不自动识别整篇正文，不替用户勾选同意，也不上传数据。上报已升级为 v2 草稿与幂等请求流程，详见[我的上报实施计划](2026-10-29-my-reports-implementation-plan.md)；辅助脱敏本身不改变上传协议或本地学习样本。

## 验证清单

原计划未记录编译和设备验证通过结果；本次文档审计只核对源码，不补写运行结论。

- [ ] 按变更执行 `./gradlew.bat :app:compileDebugKotlin` 和必要 Lint。
- [ ] 使用合成内容核对中文、数字、字母数字、标点、空白、换行和连续替换偏移。
- [ ] 检查身份证校验位、金额及日期时间格式错误反馈；不支持格式不得静默替换成其他类型。
- [ ] 检查底部面板滚动、大字体、取消未应用修改、应用后重新同意及上传前完整确认。
- [ ] 检查差异与引用，不新增测试目录、测试依赖或单元测试任务。
