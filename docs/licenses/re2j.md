# 白名单正则引擎许可

发件号码白名单使用直接依赖 `com.google.re2j:re2j:1.8` 进行线性时间整串匹配，不使用回溯型引擎处理用户规则。

- 上游：[google/re2j，re2j-1.8](https://github.com/google/re2j/tree/re2j-1.8)。
- 许可：上游 `LICENSE` 原文从该标签提取，保留上游提供的版权声明。
- 分发原文：[app/src/main/assets/licenses/re2j-LICENSE.txt](../../app/src/main/assets/licenses/re2j-LICENSE.txt)，随 APK assets 一并打包。

升级时核对版本目录、上游许可证和 APK 中的原文；不翻译或删除上游版权声明。
