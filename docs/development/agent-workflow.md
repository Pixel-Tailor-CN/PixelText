# Agent 工作流维护说明

## 规则放在哪里

根目录 `AGENTS.md` 保存跨任务生效的项目事实、边界和验证入口；`CLAUDE.md` 仅链接该入口。
编码与验证约定见[编码与验证规范](coding-and-validation.md)，作为项目规范维护，不包装成技能。开发维护文档统一放在 `docs/development/`，按当前任务读取相关章节。

GitHub Actions 定义放在 `.github/workflows/`。正式设计和执行计划放在 `docs/plans/`，仅在对应任务需要时读取；历史检查点和旧技能命令不是全仓库执行规则。
临时进度、评审和验证证据放入 `docs/.local/`，不进入版本历史。

## 按任务规模执行

明确的小改动直接实现并验证。跨模块或存在接口决策的任务先说明关键步骤、约束与验收方式，长期设计才落盘。
缺失信息会改变范围或外部影响时询问，常规可逆决策自行推进；用户已有授权无需被技能中的通用审批模板重复确认。
独立子任务可并行，指定文件归属和交付目标，避免共享文件写入冲突。验证与影响范围匹配，已有充分证据后不重复全量检查。

以上选择对应 [GPT-6 Astra 官方指导](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra) 中的自主推进、指令敏感性、委派和验证建议，是本仓库的应用方式，不是性能提升的量化保证。

## 外部技能与历史约束

项目使用的通用技能来自用户个人目录及插件；仓库内的编码与验证要求作为文档维护，不复制或改写个人技能。
当前可见的 `using-superpowers` 有“1% 相关就加载”的规则，`brainstorming` 对所有改动要求设计批准，`writing-plans` 要求固定 TDD、逐步提交及旧文档目录；这些流程与本仓库的按需加载、既有授权、不做单元测试和文档目录约定有冲突。
项目明确约定优先用于本仓库，但不会改写宿主的系统指令、工具权限或全局设置。需要跨仓库优化时，再单独维护这些个人技能；不要在仓库中新建同名技能试图覆盖它们，因为同名技能不会合并。

旧 AGENTS.md 的网络限制与现有 Hub、资源更新、样本提交设计存在差异。当前保留端侧解析、分类与敏感数据边界，明确提示核对相关请求和设计；本次不决定新的网络产品政策，也不修改应用功能。

## CI 维护

- `auto-assign-issue.yml` 仅响应新 Issue 并使用 `issues: write`；职责明确，维持原状。
- `release.yml` 仅由 `v*` 标签触发。完整 Git 历史用于计算版本；远程资源继续校验大小和 SHA-256；JDK 21、AAB/APK 输出、CHANGELOG 正文和签名清理保持原流程。
- 发布 job 显式使用 `contents: write`，避免依赖仓库默认 Token 权限。签名秘密通过步骤环境变量传入 Shell 并加引号，避免特殊字符改变命令含义。
- 工作流修改先做 YAML/Actions 静态检查；真实签名与发布结果仍需实际 CI 运行确认。审计或静态验证不自动触发发布。

## 官方依据

以下文档于 2026-09-12 查阅，后续按实际问题重新核对，不把模型名或推理档位固定为项目要求。

- [GPT-6 Astra 模型指导](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra)：行为与提示优化。
- [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)：项目指令发现与作用范围。
- [构建技能](https://learn.chatgpt.com/docs/build-skills)：精确触发、按需加载和仓库技能目录。
- [Codex 最佳实践](https://learn.chatgpt.com/guides/best-practices)：简洁项目指南、合理规划与可验证交付。
- [GitHub Actions 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)：job 权限与 Release 所需的 `contents: write`。
- [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)：环境变量与 Shell 引用。
