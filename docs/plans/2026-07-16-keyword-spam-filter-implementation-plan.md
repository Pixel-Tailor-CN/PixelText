# 关键词骚扰拦截实现与维护计划

## 当前实现

关键词管理、新短信判定和历史核对均已接入。旧计划的 `spam.db` v2 迁移、独立 `keyword_spam_match` 以及“关键词或模型”简单并集被[主库合并](2026-11-05-main-database-consolidation.md)和[持久骚扰判定](2026-11-05-persisted-spam-decision.md)替代。

| 文件（相对应用包目录） | 当前职责 |
| --- | --- |
| `data/repository/KeywordSpamRepositoryImpl.kt` | trim 和 Locale.ROOT 小写规范化、重复检查、CRUD；规则与已有镜像命中投影同事务提交 |
| `data/db/mirror/MessageMirrorDatabase.kt` / `MirrorSpamDao.kt` | 主库保存关键词规则及消息的关键词 ID、原始分数和最终分类；旧库仅作迁移来源 |
| `domain/spam/KeywordSpamRepository.kt` | 提供规则观察、保存、删除、单条匹配和重建接口 |
| `worker/KeywordSpamRebuildWorker.kt` | 读取系统 SMS 扫描快照，仓库批量比较并同步变化，再重建当前镜像投影；成功通知 Smartspacer |
| `worker/KeywordSpamRebuildScheduler.kt` | 唯一工作使用 REPLACE，观察 WorkInfo 并提供运行／失败状态 |
| `worker/SpamDetectionWorker.kt` | 校验当前短信身份，执行规则与可用模型判定；最终自动操作前再次核对规则及决策代次 |
| `viewmodel/KeywordSpamViewModel.kt` | 暴露列表、保存状态、消息与重建状态；成功 CRUD 后安排系统快照核对 |
| `ui/screen/KeywordSpamSettingsScreen.kt` | 编辑／删除对话框、添加 FAB、空状态、信息弹窗与重试入口 |

## 当前约束

- 仅匹配 SMS 正文，忽略大小写的普通包含匹配，不支持正则或组合条件。
- 白名单／持久放行优先于关键词；关键词优先于当前消息纠正和有效模型结果。模型关闭时关键词仍有效。
- 新短信关键词命中不必跳过模型计算，但不能覆盖显式放行或用旧候选执行副作用。
- 历史重建只更新分类与展示，不自动已读、删除或重新通知历史消息。
- 关键词、正文和命中结果保存在本机，不随 Hub 请求上传。
- 保持学习决策锁、镜像同步锁及主库事务的现有顺序，避免规则与消息标记分离提交。

## 验证边界

原计划未记录本功能全部编译、模拟器与端到端验收通过；本次仅核对文档与源码。

- [ ] 按改动编译及 Lint，不新增或运行单元测试。
- [ ] 使用合成 SMS 核对新增、大小写重复、编辑、删除、保存失败与重试。
- [ ] 核对显式放行、人工纠正和模型冲突，删除关键词后重新计算结果。
- [ ] 验证模型关闭时关键词继续生效，MMS 不参与关键词匹配。
- [ ] 真机确认新短信静默／自动处理、通知与列表刷新；历史核对不得触发这些副作用。
