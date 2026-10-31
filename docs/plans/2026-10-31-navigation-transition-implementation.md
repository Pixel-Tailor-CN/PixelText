# 页面转场动画自定义实施计划

> 执行方式：使用 superpowers:executing-plans，在当前会话按步骤实现。项目 AGENTS.md 的验证、文档路径和授权规则优先；不新增或运行单元测试，不设置重复审批点。

**目标：** 提供四种可持久化的页面转场预设，并统一应用导航和设置分类的转场。

**架构：** Domain 定义稳定枚举，现有设置仓库持久化并发布 StateFlow；UI 集中定义动画，根导航与设置分类共同消费。设置弹窗通过现有 ViewModel 保存偏好。

**技术：** Kotlin、Compose、Material 3、Navigation Compose、SharedPreferences、Koin。

**设计依据：** [页面转场动画自定义设计](2026-10-31-navigation-transition-design.md)。

## 全局约束

- 默认效果参数保持现状，横滑为 300 ms / FastOutSlowInEasing，淡入淡出为 200 ms / LinearEasing。
- 枚举存储值为 default、slide、fade、none，缺失及未知值回退 DEFAULT。
- 不修改首页标签转场、备份格式、Manifest 或依赖；不重建 NavHost / NavController。
- 设置分类保留 BackHandler、zIndex 方向和禁用尺寸动画；根导航显式接入全部六个转场回调。
- 新偏好仅本机持久化；不新增或运行单元测试。

## 重点复核

- 已保存值在进程重启后首次导航即生效；未知值不崩溃。
- 切换风格后从设置分类返回立即使用新值，页面和滚动状态保持。
- 预测性返回完成与取消正确，NONE 不回落到库默认转场。
- 旧备份继续可恢复，恢复不覆盖本机动画选择。
- 大字号下弹窗可滚动，单选语义完整，宽屏维持双栏。

## 任务 1：设置模型与持久化

文件均相对 `app/src/main/java/vip/mystery0/pixel/text/`。

- 新增 `domain/settings/NavigationTransitionStyle.kt`。
- 修改 `domain/settings/AppSettingsRepository.kt`、`data/repository/AppSettingsRepositoryImpl.kt`、`viewmodel/SettingsViewModel.kt`。

接口：`NavigationTransitionStyle(storageValue: String)`；`fromStorageValue(value: String?): NavigationTransitionStyle`；`AppSettings.navigationTransitionStyle`；仓库 `setNavigationTransitionStyle(style: NavigationTransitionStyle)` / `getNavigationTransitionStyle(): NavigationTransitionStyle`；ViewModel 同名 setter。

- [x] 新增枚举、字段、默认值和键 `navigation_transition_style`。
- [x] 接入现有 updatePrefs/readSettings；读取值缺失或未知时回退默认。
- [x] ViewModel 转发 setter；静态核对备份列表不包含新键，恢复只覆盖输入键。

## 任务 2：转场与设置界面

- 新增 `ui/NavigationTransitions.kt`。
- 修改 `ui/AppNavigation.kt`、`ui/screen/SettingsScreen.kt`。

接口：`navigationEnterTransition(style: NavigationTransitionStyle): EnterTransition`、`navigationExitTransition(style: NavigationTransitionStyle): ExitTransition`、`navigationPopEnterTransition(style: NavigationTransitionStyle): EnterTransition`、`navigationPopExitTransition(style: NavigationTransitionStyle): ExitTransition`。

- [x] 原样抽取现有默认函数和常量；公共函数按风格返回默认、全宽滑动、淡入淡出或 None。
- [x] 根导航注入 AppSettingsRepository，收集设置，在全部六个回调中读取当前风格。
- [x] 设置分类使用现有 settings 流及公共函数，保持导航状态、层级和 `.using(null)`。
- [x] 应用与主界面分组中图标设置后添加入口；标题「页面转场动画」，摘要显示选择名称。
- [x] 新增可滚动单选弹窗，四个选项和范围说明遵循设计，点击保存关闭，取消不保存；以整行为 selectable，避免重复单选语义。

## 任务 3：验证与交付

- [x] 运行 `./gradlew.bat :app:compileDebugKotlin`：BUILD SUCCESSFUL；已有 Slider API 和 Gradle 配置警告，无编译错误。
- [x] `git diff --check` 通过；独立代理核对全部调用点、默认参数及本地 Navigation 源码，未发现 Critical / Important / Minor 问题。
- [x] 在 emulator-5554（Android 17 / API 37，1080×2424，420 dpi，手势导航）覆盖安装 Debug APK；四种风格保存、分类返回、根导航手势完成与 CANCEL、弹窗取消和单选 checked 状态通过；强制停止重启保持选择；暗色、两倍字号、横屏双栏及选项操作通过，已查看截图。另已核对四种根导航手势中间态截图及取消恢复；系统 animator_duration_scale=0 时导航和设置分类手势取消/完成通过。TalkBack 朗读、备份实际导入导出、跨版本设备和完整连续动画录屏未验证。
- [x] 更新设计实施状态与验证限制；临时验证记录置于 `docs/.local/`。实现改动留在工作区供后续提交，设计提交独立保留。
