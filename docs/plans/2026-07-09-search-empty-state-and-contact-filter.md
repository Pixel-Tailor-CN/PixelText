# 搜索空状态与号码筛选实现计划

## 当前实现对照

早期的单联系人字段和仓库末端过滤已经由[搜索筛选重设计](2026-09-06-search-filters-redesign.md)替代。保留空状态插图及限定单个号码的需求，维护以下现有链路：

1. `domain/model/search/MessageSearchFilter.kt` 维护 `phoneNumber`／`phoneDisplayName` 等筛选字段；`MessageSearchRequest` 封装实际查询。
2. `SearchViewModel.setDomainFilter()` 更新筛选，`triggerSearch()` 取消旧任务并递增请求代次；文本输入防抖 300ms，筛选变更即时执行。无关键词但有有效筛选时不会返回 Idle。
3. `MessageRepository.searchMessages(request)` 查询镜像，`MirrorSearchQuery` 与 `MirrorSearchDao` 执行 SQL 筛选。原 `ContactDataSource.matchesAddress()` 仍可作为联系人工具存在，但不承担当前搜索的最终过滤。
4. `SearchFilterBar` 的“发件人号码”打开 `SearchPhoneScreen`；支持手动输入、系统联系人选择和清除。`PickedPhoneSource` 读取所选号码，不恢复旧的通用联系人权限申请流程。
5. `SearchResultList` 在 Idle 使用 `AppEmptyState` 和 `illustration_search_idle`，无结果使用另一幅插图。当前保留 Android 矢量资源；早期要求的原始 SVG 路径已不存在，维护时直接核对现有 drawable。

代码均位于 `app/src/main/java/vip/mystery0/pixel/text/`。旧接口 `setContactFilter()`、`clearContactFilter()`、`contactAddress`、`contactDisplayName`、顶部独立联系人按钮不再是实施要求。

## 验证边界

本文件原清单没有记载验证通过；当前文档核对不替代运行检查。历史编译和部分场景结果以[后续实施状态](2026-09-06-search-filters-redesign.md)为准。

- [ ] 按变更执行 `./gradlew.bat :app:compileDebugKotlin`，必要时 Lint。
- [ ] 核对 Idle／无结果插图、号码页保存／清除／联系人失败回退、仅号码搜索。
- [ ] 核对号码和未读、SIM、类型、日期叠加，以及旧查询取消后的状态一致性。
- [ ] 检查暗色、大字体、软键盘和返回位置；不新增或运行单元测试。
