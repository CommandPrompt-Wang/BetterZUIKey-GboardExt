# BetterZUIKey-GboardExt

把 Gboard 的语言/subtype 切换交还给框架，让 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey)
那套输入法快捷键对 Gboard 也能用。

**当前状态**：第三轮。已实现 **严格模式**（拦掉 Gboard 自己切语言，语言只由框架/BZK 决定），
另有只读探针用于上机确认时机；还没有 UI 开关（严格模式现在是编译期常量）。

- 静态分析结论、锚点清单与"不硬编码"方案：**[ANALYSIS.md](ANALYSIS.md)**
- 包名：`moe.lovefirefly.bzk.gboardext`
- 目标：`com.google.android.inputmethod.latin`（GBoard 17.2.2 / arm64）

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-GboardExt-v<versionName>.apk
```

日志：`adb shell logcat -s GboardExt`
