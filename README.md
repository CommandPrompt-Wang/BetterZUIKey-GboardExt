<div align="center">

<h1>Gboard 增强</h1>
<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="Gboard 增强">

<p></p>
<p>简体中文</p>

[![Android](https://img.shields.io/badge/API-27%2B-green)](https://developer.android.com/about/versions/8.1) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](LICENSE)

<p>把 Gboard 的语言 / 布局切换交还给框架，让 <a href="https://github.com/CommandPrompt-Wang/BetterZUIKey">BetterZUIKey</a> 那套输入法快捷键对 Gboard 也能用</p>

<p>兼容 Gboard <code>17.2.2</code> ~ <code>18.3.1</code></p>
</div>

> 君ノ声ガ　聞コエルヨ。
>
> 你的声音，我能听到呀。

**声明**：本仓库主要部分均为 AIGC，可能有缺陷，欢迎审查和 PR。

这是一个  [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey)  的扩展组件，建议与本体搭配使用以达到最佳效果。

<p><sub>应用图标基于搜狗输入法自带图标二次创作；流萤像素画来源未知，如有侵权请联系删除</sub></p>

---

## 开发动机

Gboard 确实是一款优秀的输入法，但在使用过程中，仍有这样的问题：

### 其一：喧宾夺主

Gboard 把「切语言」这件事**攥在自己手里**——地球键、`Shift` 单击中/英、它自己那份语言列表。这些动作要么在它进程内部闭环，要么由系统代它执行，**框架看不见也管不了**。

于是 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 里那套「切换到下一个输入法语言」让它根本对不上：BZK 发的是 subtype 切换，而 Gboard 自己还有一套并行的切换逻辑，两边互相打架。

### 其二：粗枝大叶

- 物理键盘的中文态下把 `｛｝`、`［］`、`＋－`、`＊＃` 等**一律按全角输出**，需要半角的场合只能切英文模式
  - 逆向 Gboard 的 `lib/arm64-v8a/libintegrated_shared_object.so` 后能看到原因：里面躺着一张覆盖**整个 ASCII 可打印区**的 1:1 全角映射表，所以"逐个把漏网的补进白名单"这条路永远补不完
  - ~~它甚至愿意做一个全量映射表都不关心中文用户的实际习惯吗有点意思~~
- 软键盘与物理键盘的引号 / 括号**都没有**自动补全
- 中文态拼音栏还有字时按 `Enter`，浏览器地址栏 / 聊天消息栏会被**整个提交掉**，极易造成误触
  - `Shift+Enter` 可以缓解，但不能解决根本问题


本模块注入 Gboard 进程，拦掉它自己的语言切换、接管中文态的标点与提交管线、补齐自动补全——以此让它和框架（以及 BZK）配合得起来。

## 功能特性

- **严格模式**：拦掉 Gboard 自己切语言 / 切布局，语言**只由框架 / [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 决定**
  - 四层挂点：客户端 `InputMethodManager` / 输入法侧 `InputMethodService` / IMMS 的 Binder 代理 / `IInputMethodPrivilegedOperations` 代理
  - 第二层拦"地球键 → 注入 `KEYCODE_LANGUAGE_SWITCH` → 系统替它切"这条路
  - 只拦"当前输入法内部的 subtype 切换"；目标是**别的**输入法时一律放行（那是换输入法，不是换语言）
  - 装不上、认不出的一律**放行并打日志**：宁可漏拦，不乱拦
- **与 BetterZUIKey 联动**：未检测到 BZK 时严格模式开关禁用；需要在 BZK 的「输入法增强 → 输入法适配管理」里把 Gboard 加进「使用系统框架」（**BZK v1.7.0 以上**），由 BZK 完全接管
- **标点管线**：把「中英字符」与「全半角」拆开，分别受不同开关控制
- **引号 / 括号自动补全**：软键盘与物理键盘**两个独立开关**，共用同一份可编辑配对表（默认 18 对）
  - 有选区时**包裹选区**（`abc` → `（abc）`）而不是替换它
  - 光标后侧已有闭字符时**只把光标移过去**，不再多吐一个；手动移动光标后也可以恢复正常闭合
- **中文态 Enter 不提交**：拼音照常上屏，但不再把输入框误提交
- **配置热生效**：广播推送 + 有界补播链，修改配置无需重启输入法
- **状态位镜像**：三个状态位（全角 / 中英标点 / 物理补全）的当前档位回传到设置页显示，可以长按条目应急切换

## 功能一览

| 功能 | 解释 | 默认值 |
|------|------|--------|
| 只响应系统框架语言切换消息 | 严格模式：拦掉 Gboard 原生切换，语言只认框架信号 | 关 ¹ |
| 完整的 `……` 和 `——` | 输入 `—` / `…` 时输出两个而不是一个 | 开 |
| 智能中文标点 | 更合理的中文标点映射（`+ - # [ ]` 等按半角处理） | 开 |
| 全角模式 | 允许在全角 / 半角之间切换　切换快捷键：`Shift+Space` | 开（状态默认**半角**） |
| 中英文标点 | 允许中文态在中英标点之间切换　切换快捷键：`Ctrl+.` | 开（状态默认**中文标点**） |
| 智能编号 | 数字后面的 `。` `）` 自动用半角，方便输入 `1.` `2)` | 开 |
| 引号 / 括号自动补全（软键盘） | 软键盘打引号、括号时自动补另一半并把光标移进中间 | 开 |
| 物理键盘自动补全 | 同上，但只认硬件按键　切换快捷键：`Ctrl+Shift+9` | 关（状态默认**开**） |
| 中文态 Enter 不提交 | 中文态按 Enter 时不再把整个输入框提交掉 | 关 |

¹ 未检测到 BetterZUIKey 时此项**禁用**（严格模式要靠 BZK 接管语言切换），强行开启会造成没有有效切换快捷键

只有三个**状态位**（全角 / 中英标点 / 物理补全）带快捷键；其余都是纯开关，改完即时生效。

> 「默认值刻意取与今天行为一致的那一档 ⇒ 加开关**零回归**」——所以严格模式、物理补全、中文态 Enter 三项默认关。

## 工作原理

模块在 Gboard 进程里做五件事：**拦语言切换** / **路由物理按键** / **改写提交内容** / **配对与补全** / **回传状态位**。

> dex 级逆向、实测数据与踩坑记录全部整理在 **[PRINCIPLE.md](PRINCIPLE.md)**。

```
模块 App（MainActivity）
    ↕ 显式广播（ConfigSender · 变更即刻发 + 有界补播链）
    ↕ 状态位反向广播（模块 → App，用于设置页显示「当前状态」）
Gboard 进程（BridgeHook）
    ├── SwitchGuard     严格模式①：IMM / InputMethodService / IMMS 代理 / 特权代理 四层挂点
    ├── KeyGuard        严格模式②：拦注入的 KEYCODE_LANGUAGE_SWITCH(204)
    ├── KeyRouter       物理按键总路由：Shift+Space / Ctrl+. / Ctrl+Shift+9 + Shift 单击守卫
    │                 └ EnterFix   中文态 Enter：改写成 Shift+Enter，再吞掉注入事件
    ├── SymbolNormHook  RemoteInputConnection.commitText / setComposingText（框架类）
    │                 └ SymbolNorm  语义层 → 宽度层
    │                 └ AutoPair    补闭字符 / 包选区 / 跳过已存在的闭字符
    └── Banner          热键反馈（不用 Toast）
```

### 设计取舍

配置通道上模块做了三个不太直观的决定，完整推导见 **[PRINCIPLE.md](PRINCIPLE.md) §9**：

- **用显式广播，而不是 ContentProvider** —— Gboard 的 `targetSdk=36`，受 Android 11+ **包可见性**限制**看不见我们的包**（`Failed to find provider info for ...`）；而可见性只约束**发起方**，反过来由我们（能看见 Gboard）发**显式广播**给它就能通。接收端在 Gboard 进程里用输入法服务自己的 Context **运行时注册**，不要求宿主 APK 声明任何东西。
- **配置要落盘到目标进程** —— 广播是"一次性"的：Gboard 一重启就回到编译期默认值，开关会**悄悄回默认**（非常神秘的特性吧？）。所以模块收到广播就顺手写进 `gboardext_state`，启动先读回当初值；再加一条**有界补播链**（`0s / 10s / 30s / 1min / 3min / 10min / 30min`）去赶"改设置那一刻 Gboard 往往没在跑"的场景。
- **状态位走反向广播** —— 三个状态位存在 **Gboard 进程**的 prefs 里，App 物理上读不到，设置页显示不出"当前是哪一档"。所以热键切换的那一刻由模块发一条**显式指定包名**的广播回来。

## 改代码前先读这个

踩过的坑都记在对应类的注释里，但有几条**反直觉且会静默失效**，建议在修改之前仔细以节约测试时间
（推导与实测数据见 **[PRINCIPLE.md](PRINCIPLE.md)**）：

- **挂 `onKeyDown` 必须挂"实例类链"** —— Gboard 覆盖了它，只挂 `InputMethodService` 只能看到 `onKeyUp`（实测：一条 `onKeyDown` 日志都没有）。**同理，挂在框架类上的钩子有一部分从来不响**（覆盖版不调 `super` ⇒ 框架实现永不执行），这是跨版本必修项，见 §7。
- **符号归一要挂 `RemoteInputConnection` 的全部重载** —— 框架类、不被混淆，但 API 33+ 的 3 参 `TextAttribute` 版本才是 Gboard 实际走的那条，只挂两参会漏掉大部分符号（§3）。
- **半角化必须用区间规则**（`FF01–FF5E → ASCII`），逐个补表是打地鼠 —— Gboard 原生 `.so` 里是一张覆盖整个 ASCII 可打印区的 1:1 全角表，`＋＝` 就是这么漏的（§4）。
- **同一个方法最多挂 64 次**（libxposed 上限）⇒ 所有 hook 都要带"装过就跳过"的旗标，否则每次会话重装会把日志刷爆（§10）。

> 想动**严格模式**或**语言门控**之前，强烈建议先通读 [PRINCIPLE.md](PRINCIPLE.md) §2 / §3 ——
> 那两节的结论是**三轮互相推翻**才收敛的，重走一遍成本很高。

## 模块安装

0. **前置条件**：已安装 [LSPosed](https://github.com/LSPosed/LSPosed) + Gboard
   （严格模式另需 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) v1.7.0 以上，未安装时该开关禁用）

| 项 | 值 |
| --- | --- |
| 包名 | `com.google.android.inputmethod.latin` |
| **兼容版本** | Gboard `17.2.2` ~ `18.3.1` |
| ABI | `arm64` |

> 模块以 `17.2.2` 为开发基线；`18.1.3` 完成了静态核验；最终在 `18.3.1` 实装 A/B 测试。
> 好消息是：除了 DexKit 的结构化查找之外，功能挂的都是**框架类 / 框架方法名**，不硬编码混淆名，所以换版本时更可能是"功能降级"而不是崩溃。
>
> 兼容版本 `17.2.2` ~ `18.3.1`，理论上该范围和接近此范围的版本都可以生效。其它遥远版本可以尝试，但不保证效果。

1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUIKey-GboardExt/releases) 下载 APK 并安装
2. LSPosed Manager 里启用模块 —— 作用域由模块**静态声明**（只有 Gboard），无需也无法手动勾选
3. **杀掉 Gboard 进程**，让 hook 生效
4. 打开模块 App，主页可见各开关；系统设置里给个**自启动**权限会让配置同步更稳
5. 若要严格模式（**需 BZK v1.7.0 以上**）：在 BZK 的「输入法增强 → 输入法适配管理」的「使用系统框架」一段里勾上 Gboard（1.7.0 起为内置项，默认关），再打开本模块的严格模式开关

## 开发构建

```bash
git clone git@github.com:CommandPrompt-Wang/BetterZUIKey-GboardExt.git
cd BetterZUIKey-GboardExt
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-GboardExt-v<versionName>.apk
```

需要 JDK 17 + Android SDK 37（`compileSdk 37` / `minSdk 27` / `targetSdk 36`），以及 [libxposed](https://github.com/libxposed/api) API 101。

- 请自备 `app-sign.keystore` 和 `keystore.properties`（与 BZK 同一套签名，都不入库）
- 运行期按**结构**（DexKit）而非写死的混淆名去找 Gboard 内部类

## 日志

```bash
adb shell logcat -s GboardExt
```

```
norm hooked 2 method(s) on RemoteInputConnection
keys: installed on <impl class> (2)
strict switch guard installed: 74 hook(s), STRICT=false
key guard: service side installed
config restored: strict=false, longMarks=true, num=true, enter=false, ...
config broadcast: strict=false, longMarks=true, num=true, enter=false, ...
hotkey Shift+Space -> fullwidth=true
pair: （ -> （） [hw]
enter: down gate enabled=false chinese=true composing=true meta=0x0 pkg=...
enter: rewrite down -> Shift+Enter pkg=...
strict: blocked injected LANGUAGE_SWITCH (total 3)
```

> 各诊断旗标（`DEV_TRACE` / `DEV_*`）默认**关**，排查时按需在对应类里打开。改 `EnterFix` / `AutoPair` 之前建议先看 `enter: down gate ...` 那一行——"第一次不生效"这类问题，直接看哪个门没开即可。

## ⚠️ 免责声明

这是一个 LSPosed 模块，直接 hook 输入法的按键、提交与语言切换链路。使用前请：

- 理解每个开关的含义再操作
- 不当配置可能导致**切不到某个语言**、标点 / 配对行为异常，或严格模式下**无法切换语言**（此时先关掉严格模式）
- 只针对 `com.google.android.inputmethod.latin` 的 Gboard `17.2.2` ~ `18.3.1` / `arm64` 实测；其它遥远版本可以尝试，但不保证效果

开发者不承担因使用本模块造成的输入异常、数据丢失或设备故障的任何责任。

## 项目结构

```
app/src/main/java/moe/lovefirefly/bzk/gboardext/
├── BridgeHook.java          # Xposed 入口：按包名过滤 + 后台线程装各 hook
├── SwitchGuard.java         # 严格模式①：四层挂点拦「当前输入法内部」的 subtype 切换
├── KeyGuard.java            # 严格模式②：拦注入的 KEYCODE_LANGUAGE_SWITCH(204)
├── KeyRouter.java           # 物理按键总路由：三个热键 + Shift 单击守卫（挂实例类链）
├── ServiceProbe.java        # 定位 IME 实现类 / 中文态判据 / DexKit 结构化查找 / 横幅视图挂载
├── SymbolNormHook.java      # 提交管线挂点：RemoteInputConnection（全部重载）+ 智能编号
├── SymbolNorm.java          # 符号归一：语义层（该出什么字符）+ 宽度层（宽窄，区间规则）
├── EnterFix.java            # 中文态 Enter：down/up 成对改写为 Shift+Enter + 吞掉注入事件
├── AutoPair.java            # 配对：补闭字符 / 包裹选区 / 跳过已存在闭字符（光标兜底）
├── GboardPair.java          # 配对表：开→闭 Map + 转义 + 自检（奇偶 / 重复）
├── GboardState.java         # 三个状态位，存在目标进程 prefs 里 + 镜像回 App
├── GboardConfig.java        # 配置结构 / 默认值 / dump + parseDump
├── BroadcastConfig.java     # 配置通道：运行时注册的显式广播接收器 + 落盘到目标进程
├── ConfigSender.java        # App 侧唯一发送入口（设置页 / 补播 / 开机共用）
├── ConfigRetry.java         # 有界补播链（AlarmManager.set，不需要精确闹钟权限）
├── ConfigRetryReceiver.java # 补播链的一环 + 开机 / 升级入口
├── ConfigWatch.java         # provider 轮询（Gboard 上走不通，默认关，留作兜底）
├── ConfigProvider.java      # provider 通道（同上，仅兜底）
├── TraceProbe.java          # 全量诊断探针：一次按键看出真实路径（默认关）
├── Banner.java              # 输入法窗口上的一行提示（Toast 会被通知设置拦掉）
└── MainActivity.java        # 首页：所有开关 + 自启动入口 + 「当前状态」显示
```

## 📄 许可证

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)
