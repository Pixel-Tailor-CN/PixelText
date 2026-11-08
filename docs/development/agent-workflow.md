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

## 外部技能与历史约束

项目使用的通用技能来自用户个人目录及插件；仓库内的编码与验证要求作为文档维护，不复制或改写个人技能。
外部技能会独立更新，具体要求以当前加载的技能内容为准，不在仓库文档中把某次技能快照描述为永久事实。若通用模板要求固定审批、单元测试、逐步提交或不同文档目录，应按当前指令优先级和项目明确约定处理。
项目明确约定优先用于本仓库，但不会改写宿主的系统指令、工具权限或全局设置。需要跨仓库优化时，再单独维护这些个人技能；不要在仓库中新建同名技能试图覆盖它们，因为同名技能不会合并。

`AGENTS.md` 如实列出 MMS、资源及发件方资料更新、用户确认的样本上报和记录查询等联网用途，同时保留端侧解析、分类、个人学习与敏感数据边界。维护文档不扩大上传范围或删除已有功能。

## CI 维护

- `auto-assign-issue.yml` 与 `auto-assign-pr.yml` 分别响应新 Issue 和新 PR 并自动指派维护者；职责明确，维持原状。
- `release.yml` 仅由 `v*` 标签触发。完整 Git 历史用于计算版本；远程资源继续校验大小和 SHA-256；JDK 21、AAB/APK 输出、CHANGELOG 正文和签名清理保持原流程。
- GitHub Release 发布 job 显式使用 `contents: write`；Google Play 内部测试和运行检测只需 `contents: read`，避免依赖仓库默认 Token 权限。签名秘密通过步骤环境变量传入 Shell 并加引号，避免特殊字符改变命令含义。
- `google-play-internal.yml` 仅对 `main` 推送且最后提交信息包含 `ci` 时发布签名 AAB 到 Play 内部测试，不创建 GitHub Release；配置见 [发布说明](google-play-publishing.md)。
- `release-runtime.yml` 在 PR 打开、更新、重新打开或手动触发时，仅检查混淆后的 vCard 解析和 TensorFlow Lite 原生库初始化，不包含 MMS、备份或界面回归。
- 工作流修改先做 YAML/Actions 静态检查；真实签名与发布结果仍需实际 CI 运行确认。审计或静态验证不自动触发发布。

## 参考资料

- [GitHub Actions 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)：job 权限与 Release 所需的 `contents: write`。
- [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)：环境变量与 Shell 引用。
