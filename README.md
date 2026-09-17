# BetterZUIKey-GboardExt

把 Gboard 的语言/subtype 切换交还给框架，让 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey)
那套输入法快捷键对 Gboard 也能用。

**当前状态**：第一轮（静态分析）——只有只读探针，**还没有任何开关**。

- 静态分析结论、锚点清单与"不硬编码"方案：**[ANALYSIS.md](ANALYSIS.md)**
- 包名：`moe.lovefirefly.bzk.gboardext`
- 目标：`com.google.android.inputmethod.latin`（GBoard 17.2.2 / arm64）

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-GboardExt-v<versionName>.apk
```

日志：`adb shell logcat -s GboardExt`
