# 原理与实现（PRINCIPLE）

> 功能、安装与用法看 [README.md](README.md)。本文是它的技术底稿：实测数据、逆向结论与踩坑记录。

把 **Gboard** 变成「框架 subtype 驱动」的输入法（语言切换交还给系统 / BetterZUIKey），
并顺手修掉它在中文态下的几个坏毛病（全角化、误提交、不配对）。

目标是**跨版本可用**：生产路径只碰**框架类与方法名**、运行期实例类、以及**结构性**关系，
代码里不出现任何混淆名 —— 所以 Gboard 换版本时更可能是"功能降级"而不是崩溃。

---

## §0 三条铁律

1. **不写死混淆名。** 定位优先级：框架方法/字段（框架类不混淆）→ 字符串常量 → 结构关系
   （super / interface / 调用链）。`nix` / `nhi` / `nei` 这类名字**只出现在注释里**作对照。
2. **认不出来就不动（fail-safe）。** 语言认不出来 ⇒ 不做归一；hook 装不上 ⇒ 放行并打日志。
   宁可漏拦，也不乱拦、不乱改。
3. **装不上、抛错只降级，绝不崩。** 每条 hook 都独立 try/catch，失败只留一行 `Log.w`。

### 跨版本核验（§0 的证据）

用自写 dex 扫描器对比三代 Gboard，**混淆名整批换了，我们的挂点一个都没动**：

| 项 | 17.2.2 | 18.1.3 | 18.3.1 |
|---|---|---|---|
| manifest 里的 IME 组件 | `com.android.inputmethod.latin.LatinIME`（**未混淆**） | 同左 | 同左 |
| 实例类链 | `LatinIME → ecy → nix` | `LatinIME → eqy → ovf` | `LatinIME → erp → ozc` |
| `onKeyDown/onKeyUp(int,KeyEvent)Z` | `ecy` 与 `nix` 两层都声明 | `eqy` 与 `ovf` 两层 | `erp` 与 `ozc` 两层 |
| 基类声明的框架方法 | `onCreateInputView` / `onStartInput` / `onStartInputView` / `onCurrentInputMethodSubtypeChanged` / `onUpdateSelection` | 签名逐条一致 | 签名逐条一致 |

实装 A/B（同一份模块代码）只差混淆名：

```
17.2.2: keys: hooked ecy#onKeyDown, nix#onKeyDown, … / keys: installed on …LatinIME (4)
18.3.1: keys: hooked erp#onKeyDown, ozc#onKeyDown, … / keys: installed on …LatinIME (4)
```

⇒ **名字型 hook 会全灭，本模块不受影响。**

**覆盖范围**：实际测试了 Gboard `17.2.2` ~ `18.3.1`（`17.2.2` 是功能实测主力、
`18.3.1` 做过实装 A/B、`18.1.3` 做过静态核验），**理论上该范围和接近此范围的版本都可以生效**。

---

## §1 分析对象与锚点

| 项 | 值 |
|---|---|
| 包名 | `com.google.android.inputmethod.latin` |
| 本模块实测版本 | `17.2.2.895242737-release-arm64-v8a`（`versionCode 175753758`，targetSdk 36） |
| **实测范围** | **`17.2.2` ~ `18.3.1`** —— 该范围及接近此范围的版本理论上都可用（逐版核验见 §0） |
| 逐版详情 | `18.3.1` 实装 A/B ✓ · `18.1.3` 静态核验 ✓（其安装包是 split base，装不上） |
| 包体 | 单个 `base.apk`，74 MB，**无 split**；dex 4 个（7.3M / 8.6M / 2.0M / 0.6M） |

### 锚点表（"想做什么 → 挂哪条"）

| 想做什么 | 锚点（**框架签名**） |
|---|---|
| IME 服务本体 | `InputMethodService` 的**子类**（运行期 `getThisObject().getClass()` 读出） |
| 会话开始 | `onStartInput(EditorInfo,Z)` / `onStartInputView(EditorInfo,Z)` |
| 键盘视图 | `setInputView(View)` / `onCreateInputView()` |
| 框架 subtype 变化 | `onCurrentInputMethodSubtypeChanged(InputMethodSubtype)` |
| **Gboard 自己切语言** | `InputMethodService.switchInputMethod(String, InputMethodSubtype)` |
| 物理按键 | `onKeyDown/onKeyUp(int,KeyEvent)`（**必须挂实例类链**，见 §7） |
| 上屏文本 | `RemoteInputConnection.commitText / setComposingText`（**全部重载**，见 §3） |
| 选区变化 | `onUpdateSelection(int×6)`（同上，必须挂实例类链，见 §7） |

### subtype 现状（决定了"为什么必须做成模块"）

- 静态声明 **143 个** subtype，**不含中日韩**（143 里只匹配到一个 Konkani 的 `kok`）；
  CJK 是 Gboard **运行期**用 `setInputMethodSubtypes` 动态加进去的，用**它自己的 uid**。
- 框架里最终注册 3 个：`简体中文` / `日本語` / `Alphabet`（Gboard 内部命名，**无 locale**）。
- ⇒ **Gboard 本来就"认 subtype"**：用 BZK 的快捷键切 subtype 时 Gboard 会跟随（实测 ✓）。
  模块**不需要**做"让框架能驱动 Gboard"，反方向（严格模式）才是要做的。

---

## §2 严格模式：为什么最后是在 Gboard 进程里拦住的

这一节是本项目最曲折的部分，**三轮结论互相推翻**，完整记下来免得重走。

### 第一轮：三层挂点，0 命中

| 挂的层 | 结果 |
|---|---|
| IMM 客户端 6 个入口（`switchToNext/Previous` / `setCurrentInputMethodSubtype` / `setInputMethod` / `setInputMethodAndSubtype`） | 0 次命中 |
| IME 自己那侧 `InputMethodService.switchToNext/PreviousInputMethod` | 0 次命中 |
| IMMS Binder 代理 `IInputMethodManager$Stub$Proxy`（**整类 74 个方法全挂** + 逐调用记日志） | 只有只读调用（`asBinder` / `isHideInputMenu` / `getEnabledInputMethodList` / `shouldForcedShowKeyboard`），**没有任何写调用** |

而每次按地球键，`onCurrentInputMethodSubtypeChanged` 都照旧来（`ja_JP → 隐式 → zh_CN`）。

**当时的结论（❌ 错了一半）**："切换是 system_server 做的，拦错进程了。"

### 第二轮：全量追踪把它翻过来

全量探针（`InputMethodManager` 242 个方法 + `InputMethodService` 228 个方法 +
`BinderProxy.transact` 带接口描述符）跑了一次真实地球键：

```
TRACE binder com.android.internal.inputmethod.IInputMethodPrivilegedOperations code=14
TRACE binder com.android.internal.inputmethod.IInputMethodPrivilegedOperations code=9
```

**修正**：Gboard 进程里确实没有 IMM/IMMS 代理的写调用 ✓，但**发起点就在 Gboard 进程内** ✗ ——
它是通过 **`IInputMethodPrivilegedOperations`**（IME 专用的特权 binder）让系统替它切的。
这个 binder 的**代理活在调用方进程**里 ⇒ 挂它等于"只拦 Gboard 自己发起的调用" ✓。

### 第三轮：一个采样事故（教训比结论值钱）

用户报"Ctrl+Space 被屏蔽了"，但日志里 `strict: blocked` 是 **0 次** ⇒ 我判断"不是本模块干的" ✗。
用户**停用模块后 Ctrl+Space 立刻恢复** ⇒ 就是本模块拦的。

根因是**采样**：我每次测试前后都 `logcat -c`，那些 `blocked` 行正好落在被清掉的窗口里。

> **教训**：清日志要么放在整个测试之前，要么就别清。别让采样条件掩盖事实。

顺带修正了一个物理直觉：**输入法有焦点时，按键先进输入法窗口** ⇒
在输入法侧拦 `Ctrl+Space` 这类组合是可行的（不是"系统级快捷键就一定绕过 IME"）。

### 最终形态：四层挂点（`SwitchGuard`）

| 层 | 挂点 | 作用 |
|---|---|---|
| ① | `android.view.inputmethod.InputMethodManager` 的目标方法 | 客户端发起 |
| ② | `InputMethodService.switchToNext/PreviousInputMethod` / `switchInputMethod` | 输入法自己那侧 |
| ③ | `IInputMethodManager$Stub$Proxy`（+ `$Stub`） | 绕开客户端直连服务端 |
| ④ | `IInputMethodPrivilegedOperations$Stub$Proxy`（+ `$Stub`） | **实测地球键的真实路径**（code=9/14） |

另外 `KeyGuard` 补一层，拦「地球键 → 注入 `KEYCODE_LANGUAGE_SWITCH(204)` → 系统替它切」这条路，
挂在 `sendDownUpKeyEvents(int)` / `sendKeyChar(char)` / `IC.sendKeyEvent(KeyEvent)` 上。

### 拦截规则（三条"不误伤"）

只有**真的"切当前输入法内部的语言/布局"**才拦，判定是**按结构而不是按名字模糊匹配**：

1. **只拦当前输入法内部的 subtype 切换**：`setInputMethodAndSubtype` / `setInputMethod` /
   `switchInputMethod` 带**目标 id** —— 目标是**别的**输入法时放行（那是换输入法，不是换语言）。
   取 id 的办法是"第一个 `String` 参数"。
   > 踩过：当成 `getArg(0)` ⇒ 这些方法**全部漏拦**。
2. **`switchToNextInputMethod(onlyCurrentIme)`**：`onlyCurrentIme=false` 必须放行。
   > 踩过：拦了连 `Ctrl+Space` 都被拦掉。
3. **Shift 单击的中/英**（Gboard 内部功能，用户明确要求不干预）：`shiftJustPressed()`
   （600ms 窗口）时不拦、也不被顺序逻辑接管。

> 反面教材：一开始用"名字含 `Subtype` / 以 `switch` 开头"做判定，结果把
> `getCurrentInputMethodSubtype` 这种**读方法**、以及 `setAdditionalInputMethodSubtypes` /
> `setExplicitlyEnabledInputMethodSubtypes` 这类**"声明有哪些语言"的集合管理**也拦了 ——
> 后者会破坏输入法的语言管理 ✗。现在只有一张硬编码白名单（`isLanguageSwitch`）。

### 为什么 BZK 不受影响

BZK 的快捷键走 **system_server 里的 IMMS**，**不经过 Gboard 进程** ⇒ 进程内 hook 拦不到它，
所以严格模式开着时 BZK 照常能切语言。这是设计上必然成立的，不是巧合。

日志：

```
strict switch guard installed: 74 hook(s), STRICT=false
switchcall imm.switchToNextInputMethod args=[…] -> BLOCK (total 5)
strict: blocked injected LANGUAGE_SWITCH (total 3)
```

---

## §3 语言状态从哪来（含一个真 bug）

符号归一的**门控**是"当前是不是中文态"。这个状态的来源错了三次才收敛。

### 三轮实测

| 轮次 | 现象 | 根因 |
|---|---|---|
| 一 | 只有第一个 `｛` 被改 | 漏了**重载**：API 33+ 给 `commitText` / `setComposingText` 加了带 `TextAttribute` 的**三参数**版本，Gboard 走那条 |
| 二 | 中文一个都不改 | 门控恒 false。语言来源写错，且"认不出来"时**清空了状态** |
| 三 | **日语被改** | 严格模式拦掉了 Gboard 的 `switchInputMethod` ⇒ 框架**不知道**语言变了，`IMM.getCurrentInputMethodSubtype()` 给的是**过期的 zh** |

### 最终方案：两个来源都要

| 来源 | 覆盖场景 | 实现 |
|---|---|---|
| `InputMethodManager.getCurrentInputMethodSubtype()`（**公开 API**） | 框架驱动的切换（BZK 快捷键） | `ServiceProbe.refreshLangAsync()`，后台线程 |
| `InputMethodService.switchInputMethod(String, InputMethodSubtype)` 的**参数** | Gboard 自己切语言（**正是被 strict 拦掉的那条**） | `learnLang(st)`：拿参数的 `hashCode` 反查 |

- hash → 语言 的表来自 `getEnabledInputMethodSubtypeList(info, true)`（**公开 API**）：
  实测 `zh_CN → 617035939`、`ja_JP → -1318396357`，与 `settings get secure enabled_input_methods`
  里那两个 hash 完全一致 ✓
- Gboard 那个**本来就不带 locale** 的默认 subtype（`-1097284911` / `be98c2d1`）查不到 ⇒
  **保持现状**（不清空）

> **踩坑**：`InputMethodService.getCurrentInputMethodSubtype()` 是 `@hide`，编译期看不到
> （直接调报 cannot find symbol），但运行期是 public ⇒ 反射可用。方法名属框架、不被混淆。

### bug：框架的"老消息"会覆盖刚学到的语言

严格模式拦掉 `switchInputMethod` 后，框架一直以为还在中文。于是**只要换个输入框 / 切个 App**，
`onStartInput` 触发的那次刷新就把我们刚学到的 `ja-JP` 打回 `zh-CN` ⇒ 日语符号被归一半角 ✗。

**修法**（`refreshLang`）：记住上次从框架读到的 hash，**hash 没变就不许覆盖** ——
只有框架值**真的变了**（= 一次框架驱动的切换）才采纳。

```
lang: 框架仍说 zh-CN（未变，保留 ja-JP）
```

修复后实测连续 5 次会话开始都保住 `ja-JP` ✓。

### 被证伪的"已知边界"

曾担心"用 Gboard 自己的键切语言 → 冷启动时框架值过期"。受控实验（force-stop → 立刻抢回 IME →
不切语言直接看）**证伪**：**Gboard 冷启动按框架/默认状态初始化，不保留上次的内部语言** ⇒
冷启动时两边必然一致，这条边界不存在 ✓。

### 另一条教训

> **"认不出来" ≠ "不是中文"。** 早期在认不出来时清空状态，结果把中文一起关掉了。
> 真正需要防的"日语被改"，靠的是 `switchInputMethod` 那条把 `ja_JP` 学到手。

---

## §4 符号归一：语义层 + 宽度层

### 全角符号的来源（静态定案）

用户观察：**软键盘的全半角是对的，物理键盘才错** —— 这是个"物理键盘特供 bug"。

证据链：

1. Gboard 的 4 个 dex 里**没有任何全角符号串**（`＋＝｛＠` 全 0 命中，也没有 `Halfwidth-Fullwidth` /
   `65248` / `0xFEE0`）。⚠️ 但**别据此得出"不是查表"** —— 我下过一次，错了。
2. 原生引擎 `lib/arm64-v8a/libintegrated_shared_object.so` 里有一对**紧挨着**的字符串：

   ```
   !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~ 
   ！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～
   ```

   ⇒ **ASCII 可打印区 `0x21–0x7E` → `FF01–FF5E` 的 1:1 全角表**（经典 ±0xFEE0 关系）。
3. 另有 5 个 `res/*.xml`（键盘布局）带全角符号 ⇒ 那是**软键盘长按列表**，不是主输出。

**推论**：硬件键的字符走进原生那张表 ⇒ 物理键盘全角；软键盘键面标签本来就是半角。

### 所以：半角化必须用区间规则

`FF01–FF5E → ASCII`，只排除中文标点 `！？；：，（）`（它们本来就该是全角）。

- 早先是"逐个补表"（11 → 15 对），结果漏了 `＋＝`（用户报的）—— 因为原生表覆盖**整个**
  ASCII 可打印区，**补表是打地鼠**。
- 好消息：区间规则对软键盘**天然是 no-op** ⇒ **不需要区分"提交来自软键盘还是物理键盘"** ✓

### 全角化刻意只动符号、不动字母数字

`toFullWidth` 只碰 `0x21–0x2F` / `0x3A–0x40` / `0x5B–0x60` / `0x7B–0x7E`。

> 拼音串与英文候选也走 `commit` / `setComposing`，若连字母一起全角化，**中文态打字会变成全角拼音**。
> 这是与搜狗那边"整段 ASCII 都全角化"的**有意差异**。

### 两层结构与开关

| 层 | 内容 | 受谁控制 |
|---|---|---|
| **语义层** | `` ｀``(FF40) → 姓名圆点 `·`；`＿`(FF3F) → 破折号 `—`（`longMarks` 时 `——`）；`…` 的**连续段** → 1 / 2 个（**幂等**） | 「智能中文标点」+「完整的 …… 和 ——」 |
| **宽度层** | `FF01–FF5E` ↔ ASCII 区间互转 | 「全角模式」状态位 |

`…` 用"段"而不是"逐字符翻倍"是为了**幂等**：一次提交里已经有两个不会被翻成四个。

### 中文标点 → ASCII（"中英文标点"切到英文那一侧）

`，。！？；：（）【】《》〈〉“”‘’` → `,.!?;:()[]<><>""''`。

**不收 `、`**：它的物理键在 Gboard 上分不出来（§6），宁可不动。

### 与搜狗模块方向相反（别搞混）

| | 搜狗 OEM 版 | Gboard |
|---|---|---|
| 反引号键原生输出 | `·`（姓名圆点，**本来就对**） | `｀`(FF40)（全角化） |
| 本模块要做的事 | 把 `·` **还原**成 ASCII 反引号（我们自己的覆盖） | 把 `｀` **改成** `·` |

⇒ 同一个功能，**方向相反**。Gboard 侧这个功能本质是**修它的全角化**。

### 零回归

三个开关默认开 + 两个状态默认取"今天的行为"（半角 + 中文标点 + 语义层生效）⇒
升级后不改变任何既有输出。

---

## §5 中文态 Enter 不提交（`enterCommitPinyin`，默认关）

**要解决的问题**：在"按回车就提交"的输入框（浏览器地址 / 搜索框、聊天消息栏）里，
拼音栏还有字时按 Enter 想保留原始拼音，结果整个框被提交掉了。

### 机制（探针实测）

| 场景 | 物理键 | Gboard 行为 | 注入给 App 的键 | 结果 |
|---|---|---|---|---|
| 拼音有字 + Enter | `meta=0x0` | 吞掉 down（`onKeyDown -> true`）、`commitText` 原始拼音 | `sendKeyEvent action=1(UP) meta=0x0` **只一颗抬起** | 提交 ✗ |
| 拼音有字 + Shift+Enter | `meta=0x41` | 同上 | `action=1 meta=0x41` | 不提交 ✓ |
| 拼音为空 + Enter | `meta=0x0` | 不 commit | `action=0(DOWN) + action=1(UP)` | 正常提交 ✓ |

全程**没有** `performEditorAction` / `sendDefaultEditorAction` ⇒ 提交不是 IME action，
而是 Gboard **自己往 App 注入键事件**；**带不带 Shift 决定 App 提不提交**。

### 做法（四步，都不自己上屏）

1. 判据：中文态 && `composing`（`setComposingText(非空)` 置位、`commitText` 清位）
   && Enter && 无 Shift/Ctrl/Alt。
2. 把 Enter 的 **down 与配对的 up 都补 `META_SHIFT_ON`** 交给 Gboard ⇒ 它走自己的 Shift+Enter
   路径（拼音照常上屏、注入的抬起带 Shift）。
3. 再把这次"给拼音上屏用的"注入事件**吞掉** ⇒ 对"只认注入事件"的框兜底。
4. **不自己 commit 拼音** —— 交给 Gboard，它引擎内部状态才同步（手动 commit 有脱同步风险）。

> **踩坑：up 必须 sticky。** down 之后 Gboard 的 `commitText` 已把 composing 清成 `false`，
> 只按 composing 门控就会漏掉 up ⇒ 注入的抬起不带 Shift ⇒ **照样被提交** ✗。
> 另外拼音栏为空时要清掉"吞注入"旗标，免得残留状态把后面真正该提交的那颗也吞了。

### 已知边界

- **软键盘**上的 Enter 不走 `onKeyDown`，不覆盖。
- **QQ 消息栏**：IME 侧**无解**。专为它跑的那轮里 `ENTER key seen` 一行都没有
  （同进程 Edge 有）⇒ 说明 QQ 在**自己进程里**就把回车吃了、根本不递给 IME
  （按 Enter 后反复重开会话 = "发送 + 清空 + 重新聚焦"）。"补 Shift"和"吞注入"对它均无效。
  日志判据就是这个：一行都没有 ⇒ 别在 IME 侧找答案。

```
enter: down gate enabled=true chinese=true composing=true meta=0x0 pkg=com.microsoft.emmx
enter: rewrite down -> Shift+Enter pkg=…
enter: swallowed injected Enter action=1 pkg=…
```

---

## §6 引号 / 括号自动补全（`autoPair` 默认开 / `physComplete` 默认关）

**与搜狗相反**：Gboard **原生没有**配对行为 ⇒ 两个开关都是模块**自己实现**（搜狗那边是"关掉原生"）。

### 两条路，共用一份表与一段注入代码

| 开关 | 管的来源 |
|---|---|
| `autoPair` | 软键盘 |
| `physComplete` | 物理键盘（只认"硬件按键期间"的提交） |

判据天然分得开：**软键盘不走 `onKeyDown` / `onKeyUp`**（`KeyRouter` 在按键钩子里置 `sHwKey`）。

### 配对表用 Map，方向性由结构保证

`GboardPair` 把"相邻两字符一组"的串解析成 `Map<开字符, 闭字符>`：

- 查表 O(1)；
- **闭字符根本不是 key** ⇒ "打闭字符却补出开字符"这类问题**结构上不存在**；
- 换行只当**分组**（`clean()` 去掉），所以"只改分组换行"不影响结果；
- 表串进签名 ⇒ 改表即热重载；`dump` 走 URL 转义（`&` / `=` 会破坏 `k=v&k=v` 行格式）；
- 自检：清洗后长度必须**偶数**（奇数会把最后一对切坏，而解析是**静默丢弃**的）。

### 注入配方

原提交完成后：`beginBatchEdit → commitText(close, 1) → endBatchEdit → 光标左移`。

- 左移只在能拿到**"光标前完整文本"**时做：`getTextBeforeCursor(4096)`，**触顶就宁可不动**
  （宁可降级，也不用假偏移把光标跳错地方）。
- 注入期间 `ThreadLocal` 置位 ⇒ 标点管线见到自己注入的闭字符**原样放行**，不被二次改写。
- **同字符对**（`""` / `''`）靠模块**自己的翻转状态**决定这次是"开"还是"闭"。

### 有选区 ⇒ 包住选区（而不是替换它）

选中 `abc` 打 `（` ⇒ `（abc）`（光标落在闭字符之后），软 / 物理两条路同一套。

> **必须在 `chain.proceed()` 之前**问 `getSelectedText(0)` —— 开字符一旦提交上去，
> **选区就被顶掉了**，之后再也问不到 ✗。

- 命中即**不让原提交走**（`return Boolean.TRUE`），改由模块提交 `open + 选区 + close`；
- 超大选区（>500 字）不重提交；失败一律回退老路。

### 跳过已存在的闭字符

光标后侧**已经是同一个闭字符** ⇒ 只把光标移过去，不再多吐一个。

- 记录的是"**刚补出的是哪个字符**"，不是偏移 —— 偏移会被 `moveCursorLeftOne`、输入法自己、
  以及**用户点击**改掉，记了转眼就过期。
- 用户**手动把光标点到别处** ⇒ 上次补全作废（靠 `onUpdateSelection` 区分"用户点的"与
  "我们自己挪的"）。**注意这条依赖 §7 的挂点修正**。

### 两个"静默失败"型的坑

1. **配对表根本没发出去**：把发送逻辑从 `MainActivity` 挪进 `ConfigSender` 时，补 extra 的
   字符串替换**锚点没匹配上**（构建照样过）⇒ `EXTRA_PAIR_TABLE` 从未发送 ⇒ 模块拿 `null`
   ⇒ 解析出**空表** ⇒ 永远不配对。
   > **教训：跨进程配置要么有自检，要么有回退。** 现在广播缺表回退内置默认、`setTable` 拒绝空表。
2. **`sHwKey` 只置 true 不复位**：按过一次物理键后，所有**软键盘**提交都被判成"物理来源" ⇒
   而 `physComplete` 默认关 ⇒ 直接 return ✗。改成**按下 true / 抬起 false**。

### 热键曾经是"空开关"

`Ctrl+Shift+9` 关掉后打 `（` 仍出 `（）`：`AutoPair.maybeInject()` 的物理分支**只判功能开关**
`sPhysEnabled`，而 `GboardState.physComplete()`（状态位）只被热键自己和横幅读过，行为里一次没读。

**修法**：`hw ? !(sPhysEnabled && GboardState.physComplete()) : !sEnabled`。

> **教训：状态位与热键要成对检查。** 另外两个热键（`fullwidth` / `enPunct`）是对的，
> 因为它们被 `SymbolNormHook` 读。

```
pair table: ok (18 pairs), 18 opener(s)
pair: （ -> （） [hw]
pair wrap: （…） around 3 char(s) [soft]
pair closeSkip: caret only -> 12
pair closeSkip: caret moved 11 -> 3
```

> 首行（表自检）常开；后四行来自 `AutoPair.DEV_TRACE`，默认关。

---

## §7 两个"钩子沉默地死掉"的坑（跨版本必修）

**Xposed 挂的是「方法」，不是「虚分派」。** Gboard 覆盖了某框架方法且**不调 `super`**
⇒ 框架实现永不执行 ⇒ **挂在框架类上的那条钩子从来不响**（不报错、不崩，就是静默失效）。

静态核查（三代同形，所以不是新版回归）：

| 框架方法 | Gboard 是否覆盖 | 覆盖版调 `super`？ | 我们那条钩子 |
|---|---|---|---|
| `setInputView` | 否 | — | **活**（`Banner` 初始化就靠它） |
| `switchInputMethod` | 否 | — | **活**（学语言） |
| `onCurrentInputMethodSubtypeChanged` | 是 | **是** | **活** |
| `onKeyDown` / `onKeyUp` | 是 | 是 | 活（但真热键在 `KeyRouter` 的实例链钩子） |
| `onStartInput` / `onStartInputView` | 是 | **否** | **死**（开局的 `refreshLangAsync` 没了） |
| `onCreateInputView` | 是 | **否** | **死**（`Banner.attachView` 靠 `setInputView` 兜住） |
| `onUpdateSelection` | 是 | **否** | **死**（closeSkip 的"手动移光标作废"失效） |

框架侧的调用证据（`framework.jar` 里就一条 `invoke-virtual`）：

```
InputMethodService$InputMethodSessionImpl.updateSelection(IIIIII)V
    invoke-virtual → InputMethodService.onUpdateSelection(IIIIII)V
```

`invoke-virtual` ⇒ 分派到最派生的覆盖版 ⇒ **框架体不执行**。

### 修法：沿「实例自己的类链」再挂一遍

`KeyRouter`（按键）与 `ServiceProbe.hookSelectionUpdateOnImpl()`（选区）都是同一套办法：
从 `getThisObject().getClass()` 往上走，逐层挂 `onKeyDown/onKeyUp` 与 `onUpdateSelection`，
**跳过框架类本身**；挂到 ≥1 个就把标志置上，框架那份退化成纯放行（免得调 `super` 的机型回调两遍）。

```
selection: hooked ozc#onUpdateSelection
selection: installed on com.android.inputmethod.latin.LatinIME (1)
```

**为什么两层 `onKeyDown` 不会重复处理**：命中热键时返回 `Boolean.TRUE` **消费**掉 ⇒ 不进第二层；
Enter 改写那条即使放行到第二层，也会因为 `interceptEnter` 见到 `META_SHIFT_ON` 而提前 return；
其余写状态位的操作都是**幂等**的。

### 诊断锚点会随版本消失

DexKit 探针用的字符串 `／`(U+FF0F) 在 `18.1.3` / `18.3.1` 里**已经不存在**（`、`U+3001 还在）
⇒ 只影响 `DEV_INPUT_TRACE` 那条诊断（本来就关着）。以后要用就把锚点改成只留 `、`。

---

## §8 提交层拿不到"物理键身份"（负结果，但很值钱）

### 起因

用户要「顿号映射」三档：`\`（Gboard 原生）/ `/` / 全部。
问题：`\` 键与符号页 / 候选点出来的 `、`，**提交的字符完全相同**，而两者**要区别对待**。

### 逐层排查（每一层都证实"信息已丢"）

| 尝试 | 结果 |
|---|---|
| 软键盘按键探针（`sendKeyEvent` / `sendKeyChar` / `sendDownUpKeyEvents`） | 按 `\` 时**一条都没有** ⇒ 软键盘不给 KeyEvent ✗ |
| 找"按键 → 输出"的查表类（`usingStrings("、"/"／")`） | 命中的是**数据表**（中文同义/别名表 `qkf`、日文变体字标签表 `gvi`），不是按键管线 ✗ |
| 找"谁直接调框架 `IC.commitText`" | 全 4 个 dex **0 处** ⇒ Gboard 走**自己的 IC 包装类** ✗ |
| **栈回溯探针**（提交带 `/` `、` `／` 时打前 12 帧） | 三条路径**栈完全一样** ✗ |
| 扒提交 lambda（`Lmn` / `Lmza`） | 是 **R8 合并出的 lambda 分发器**：一个类塞几十个 lambda 体，`run()` 用字段 `d` 做 switch；构造点被**所有** lambda 共用 ⇒ hook 它区分不出哪个键，还会被全 Gboard 的 lambda 淹没 ✗ |

典型栈（三条路径一模一样）：

```
probe stack[、] thread=ICWrapper-1
      moe.lovefirefly.bzk.gboardext.SymbolNormHook.lambda$hook$0
      android.telephony.Hoorsop.commitText     ← 混淆后的框架类名
      nei.f:8                                  ← 提交漏斗
      mn.run:21                                ← 三条路径都走这个 Runnable ✗
```

### 结论与取舍

| 方案 | 可行性 |
|---|---|
| 在提交层区分物理键 | **不可能**（信息已丢，双重印证） |
| hook 键盘视图的触摸分发 | 理论可行，但键盘是自绘内部实现，需继续深挖且版本敏感 ✗ |
| 保留 `/` 档（近似） | 符号页 / 候选的 `、` 也会变 ✗ —— 用户**明确不接受** |
| **砍掉 `/` 档，只留 `\`（默认）与「全部」** | 两者都是**纯字符级**、可精确实现 ✓ |

⇒ 最终**删除**了顿号映射（用户拍板）。DexKit 的**结构定位能力留在代码里**（打开 `DEV_INPUT_TRACE`
即用），以后要用不必重走这段。

### 顺带留下的三条 DexKit 经验

1. **native 库不会自动加载**：模块跑在 Gboard 进程里，系统不会把模块 APK 的 lib 目录加进
   Gboard 的 library path ⇒ `DexKitBridge.create()` 直接 `UnsatisfiedLinkError`。
   解法：`getModuleApplicationInfo().sourceDir` → 按 ABI 从模块 APK 取出 `lib/<abi>/libdexkit.so`
   → 落到宿主 App 的 `cacheDir` → `System.load(绝对路径)`。
   `getProtectionDomain().getCodeSource()` 在 LSPosed 下**是 null**，只能走前者。
2. **必须用「服务实例」的 classloader**：`svc.getClassLoader()`（框架类 `InputMethodService`）
   是 boot loader，**看不见 Gboard 的类** ✗。
3. **`MethodMatcher.addInvoke(String)` 解析不了默认包（无包名）混淆类**
   （`IllegalStateException: Unknown class sign: nec`）⇒ 自己拆描述符拿 `Method`。
   另注 `MethodData.getDescriptor()` **返回的就是完整签名**（`Lnec;->run()V`）。

成本：APK 每个 ABI 多 **381 KB** native 库；模块在目标进程首次跑一次 DexKit 约 **0.4s**（后台线程）。

---

## §9 配置通道：为什么是广播（而不是隔壁那套 provider）

按隔壁 SogouOEMExt 的设计做了 SharedPreferences + ContentProvider + 2 秒轮询，**在 Gboard 上失败**，
根因**不是权限而是包可见性**：

```
E ActivityThread: Failed to find provider info for moe.lovefirefly.bzk.gboardext.config
D GboardExt: provider: empty cursor
```

- Gboard 的 `targetSdk = 36` ⇒ Android 11+ 的**包可见性过滤**生效 ⇒ 它**看不见**我们的包 ✗
- 隔壁能用是因为**搜狗的 targetSdk 是 29**（不过滤）
- 另一个先踩到的坑：用 `ActivityThread.getSystemContext()` 起轮询也不行 —— 它自报包名 `android`，
  provider 会以 `Given calling package android does not match caller's uid` 拒绝 ✗
  ⇒ 必须用**输入法服务自己的 Context**（包名才是 Gboard）

### 替代通道：显式广播（当前方案）

包可见性只约束**发起方** ⇒ 反过来由我们（能看见 Gboard）发**显式广播**就能通；
接收端在 Gboard 进程里用输入法服务的 Context **运行时注册**
（`RECEIVER_EXPORTED`，targetSdk 34+ 必须显式声明），**不要求宿主 APK 声明任何东西**。

> **更正一条早期结论**：一度以为"发给别人也得能看见它"，于是在清单里加了
> `<package android:name="com.google.android.inputmethod.latin"/>` 的 `<queries>`。
> 后来实测**不需要**：把整个 `<queries>` 删掉，App 发的显式广播照样送到 Gboard ✓。
> 包可见性拦的是"查 / 拉起别人的组件"，**不拦发送广播**。
>
> **但 `<queries>` 仍然留着**，因为另一件事需要它：严格模式要 `getPackageInfo` **探测 BZK 在不在**
> （不声明会被挡住 ⇒ 误报"未检测到"）。

### 配置落盘到「目标进程」

广播是"一次性"的：Gboard 进程一重启就回到编译期默认值，开关会**悄悄回默认**
（`enterCommitPinyin` 尤其致命：默认关 ⇒ 用户明明打开了却时不时失效）。

⇒ 模块收到广播时顺手把配置写进**目标进程自己的 prefs**（`gboardext_state`），启动时先读它当初值。

### 补播链（因为接收器只在 Gboard 活着时存在）

"改设置的那一刻"Gboard 往往并不在跑（用户先拨开关，之后才去打字）⇒ App 侧排一条**有界**重试链：

```
0s / 10s / 30s / 1min / 3min / 10min / 30min
```

用 `AlarmManager.set`（**非精确**）而不是 `exact`：不需要 `SCHEDULE_EXACT_ALARM` 权限，
差几分钟无所谓 —— 目标是"覆盖用户接下来这半小时里的第一次打字"。

> **实测边界**：这条链在**这台 ROM** 上只能覆盖"我方 App 进程还活着"的窗口 —— ZUI 的自启动管理
> 拒绝为广播冷启动我方进程（`ZuiAutoRunManager: isAllowStart not allow start ...`），
> 且设备上还有 Thanox 在跑。彻底覆盖要在 ROM 侧放行自启动；**保底是"开着键盘打开一次 App"**。
> 这也是设置页里那个「自启动权限」入口存在的原因。

### 状态位为什么走「反向广播」

三个状态位存在 **Gboard 进程**的 prefs 里，App **物理上读不到** ⇒ 设置页只能显示"这个功能开没开"、
显示不了"当前是哪一档"。所以热键切换的那一刻，模块发一条**显式指定包名**的广播回去
（不指定包名会被包可见性丢掉）；App 收到后落盘到镜像文件，进页面先读它。

---

## §10 状态位与热键

| 状态位 | 默认 | 快捷键 | 功能开关关掉时 |
|---|---|---|---|
| `fullwidth` 全角/半角 | 半角 | `Shift+Space` | 恒半角 |
| `enPunct` 中文/英文标点 | 中文标点 | `Ctrl+.` | 恒中文标点 |
| `physComplete` 物理补全 | 开 | `Ctrl+Shift+9` | 恒关 |

**为什么要分成"功能开关"（在 UI）和"状态位"（不在 UI）两层**：
功能开关决定"这个功能启不启用"，状态位决定"启用后当前是哪一档"，由快捷键切换并持久化。
默认值刻意取**与今天行为一致的那一档** ⇒ 加开关**零回归**。

### 热键都在 `KeyRouter` 一个挂点上

同一个方法挂两次 hook 虽然能跑，但**顺序不可控**，而这里两件事有先后关系 ——
热键（`Shift+Space` / `Ctrl+.` / `Ctrl+Shift+9`）先判，判不掉再交给中文态 Enter 的处理。

### Shift 单击守卫（必需）

Gboard 中文态下 **`Shift` 单击 = 切中/英**，而它判"单击"看的是**抬起**事件
（用户实测：`Shift+Space` 会连带把语言切了）。

⇒ 规则：Shift 与别的键组成组合键后，把它那次**抬起吞掉**。
代价：单独按一下再松开 Shift 仍然照常切语言（那才是用户想要的）。

### 提示用横幅，不用 Toast

搜狗那边实测 `Toast` 会被系统按**应用通知设置**拦掉
（`NotificationService: Suppressing toast ... by user request`），所以照同一套做法：
自己在输入法窗口上加一个 `PopupWindow`，贴底居中、约屏幕高 12%、1.2 秒后消失。
视图在 `setInputView` / `onCreateInputView` 时由 `ServiceProbe` 塞进来。

```
hotkey Shift+Space -> fullwidth=true
hotkey Ctrl+. -> enPunct=true
hotkey Ctrl+Shift+9 -> physComplete=false
shift: swallowed up (used with another key)
```

### 一个跨进程上限要记着

**同一个方法最多挂 64 次**（libxposed 上限）。早先没有"装过就跳过"的旗标，每次会话都重装一遍，
撞上限后开始刷 `IllegalStateException: Reject hook registration ... already registered 64 times`
（一次会话几百行，把日志淹了）⇒ 所有 hook 现在都带 `sXxxHooked` 旗标。

---

## §11 能力边界：进程内 vs system_server

这条边界决定了"哪些需求只能由 BZK 做"：

| 路径 | 谁在发起 | 模块能做什么 |
|---|---|---|
| 地球键 / 空格长按 / `Ctrl+Space` / 语言列表（Gboard 进程内） | **Gboard 自己** | **能拦、能接管** ✓ |
| BZK 的输入法快捷键 | **system_server 的 IMMS** | 只能"围观"（日志能看到结果），**改不了"下一个是谁"** ✗ |

- 反过来也成立：BZK 拦不到 Gboard **进程内**、不落到框架的手势（地球键）⇒
  这是**覆盖范围**问题，不是协议有洞。
- 所以：**语言顺序归 BZK**（它自己算目标 + 点名 `setInputMethodAndSubtype`），
  模块只管"有哪些语言能被切"（subtype / 框架状态）。
- 三方之间只有一份合同：**输入法把"能被切换的语言"以框架 `InputMethodSubtype` 暴露出来**
  （身份由框架字段 `locale` / `languageTag` / `mode` 决定）；**BZK 只读已启用列表、读当前 subtype、
  点名切换**；两边**不交换任何私有数据**。
  唯一例外：本模块 `getPackageInfo` **单向探测 BZK 是否存在**（给严格模式定默认值）。

> **为什么 BZK 不能用框架的 `next`**：它不是"遍历语义"，而是按最近使用/分组的 rank
> （`dumpsys input_method` 为证，三门只轮到两门）⇒ 必须自己算目标再点名切。

---

## §12 开发期开关

全部默认**关**，排查时按需在对应类里打开：

| 常量 | 类 | 作用 |
|---|---|---|
| `DEV_SERVICE_TRACE` | `BridgeHook` | 框架锚点的调用时机（含 subtype / KeyEvent / EditorInfo 详情） |
| `DEV_INPUT_TRACE` | `BridgeHook` | 输入路径诊断 + **DexKit 结构化定位**（§8） |
| `DEV_TRACE` | `SymbolNormHook` / `EnterFix` / `AutoPair` / `BroadcastConfig` | 提交改写对 / Enter 判定 / 配对注入 / 状态位镜像 |
| `DEV_TRACE_AIDL` | `SwitchGuard` | aidl 层每次调用（当初找地球键路径用的） |
| `DEV_TRACE_KEYS` | `KeyGuard` | 每一次 `sendKeyEvent`（软键盘打字时会非常多） |
| `ON` | `TraceProbe` | 全量探针：IMM / IMS 每个方法 + `BinderProxy.transact` |
| `USE_PROVIDER` | `ConfigWatch` | 启用 provider 轮询（Gboard 上走不通，见 §9） |

> **教训：实验开关要分层。** 探针的**安装点**原本只看 `DEV_ENTER_TRACE`，关日志时改写一起失效，
> 导致一轮"QQ 还是发了"是**假结论**。**"装不装"与"打不打印"必须分开。**

`EnterFix` 那条 `enter: down gate ...` 是刻意常开的（只有 Enter 会打，不吵）：
"第一次不生效"这类问题，直接看哪个门没开即可。

---

## §13 已知边界

- 按 **Gboard `17.2.2` / `arm64`** 实现并实测；**兼容 `17.2.2` ~ `18.3.1`，
  理论上该范围和接近此范围的版本都可以生效**（挂点核验见 §0）。
  其它遥远版本可以尝试，但不保证效果；非 arm64 没有 DexKit native（只影响诊断）。
- 符号归一**只在中文态**（`zh*`）生效；认不出语言时**什么都不做**（fail-safe）。
- 严格模式开着时，**Gboard 屏幕上的语言键 / Shift 中英不再能改语言**（"只接受框架信号"的代价）。
  要恢复就关掉严格模式开关（hook 常驻，每次调用读开关 ⇒ 即时生效）。
- 中文态 Enter **不覆盖软键盘**；**QQ 消息栏**在 IME 侧无解（§5）。
- 配对依赖"提交发生在硬件按键处理期间"；若某输入法把提交延后到该窗口之外则不会注入
  （宁可不补也不误补）。
- 补播链覆盖不了"App 进程已被杀且 ROM 拒绝自启动"的窗口（§9）。
- `onStartInput` / `onCreateInputView` 两条框架钩子在 Gboard 上**本来就是死的**（§7），
  相关能力分别由 `setInputView` 与 init 兜住；改动这两处时注意别依赖它们。

### 踩过的坑：配置默认值曾经有三套（已统一）

同一个开关的"默认值"曾写在**三个**地方，且没有单一来源：

| 位置 | 含义 |
|---|---|
| `MainActivity` 各 `addSwitch(...)` 的 `checked` 实参 | **界面显示**成什么 |
| `ConfigSender.send()` 里 `getBoolean(KEY_x, default)` | **发给模块**的是什么 |
| 模块侧编译期（各 `sXxx` 字段初值 / `BroadcastConfig` 的 `getBooleanExtra` 默认 / `GboardConfig` 的 `defaults()`·`parseDump()` 初值） | **还没收到广播时**是什么 |

曾经有两处对不上：

| 键 | 界面显示 | `ConfigSender` 曾发送 | 后果 |
|---|---|---|---|
| `strict` 严格模式 | 关 | `true` | 界面显示"关"，**实际是开的** |
| `autoPair` 软键盘配对 | 开 | `false` | 界面显示"开"，**实际是关的** |

**为什么很久没被发现**：`addSwitch` 里 `setChecked()` 在**挂监听器之前**调用
（AOSP 的 `CompoundButton.setChecked()` 本来**会**回调监听器，但那一刻还没挂），
所以"首次渲染"**不会**把默认值写进 prefs ⇒ `ConfigSender` 永远读到它自己的 default。
只有用户**手动拨一次**开关，prefs 才有值、两边才对齐。

**已修**：三处全部对齐到设置页的默认值 ——
`strict` 一律 `false`（`ConfigSender` / `BroadcastConfig.getBooleanExtra` / `GboardConfig.parseDump`
初值 / `SwitchGuard.sStrict` 字段初值），
`autoPair` 一律 `true`（同上再加 `AutoPair.sEnabled` 字段初值）。

> **规矩（以后照这个来）**：**默认值只允许有一处来源。**
> 模块侧所有"收到广播之前"的初值，必须等于设置页里那个开关的默认档；
> 同一类问题在 §6 有前科（**配对表没发出去** ⇒ 模块收到 null ⇒ 静默不配对）。
> 跨进程配置要么有自检、要么有回退，要么保证默认值唯一。

---

## 附：构建与仓库

```bash
git clone git@github.com:CommandPrompt-Wang/BetterZUIKey-GboardExt.git
cd BetterZUIKey-GboardExt
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-GboardExt-v<versionName>.apk
```

- JDK 17 + Android SDK 37（`compileSdk 37` / `minSdk 27` / `targetSdk 36`）+ libxposed API 101
- 自备 `app-sign.keystore` + `keystore.properties`（与 BZK 同一套签名，都不入库）
- `module.prop`：`minApiVersion=101` / `targetApiVersion=101` / `staticScope=true`；
  作用域由 `scope.list` **静态声明**（只有 Gboard），无需也无法手动勾选

| 项 | 值 |
|---|---|
| 包名 | `moe.lovefirefly.bzk.gboardext` |
| 仓库 | `git@github.com:CommandPrompt-Wang/BetterZUIKey-GboardExt.git` |
| 日志 tag | `GboardExt` |

### CI（与 BZK 同一套）

| workflow | 触发 | 做什么 |
|---|---|---|
| `android-ci.yml` | PR → `main` | `./gradlew assembleDebug` |
| `nightly-build.yml` | push 到 `dev` 且 commit 以 `[Nightly]` 开头 | CI 密钥签名 + 上传 artifact |
| `release-apk.yml` | GitHub Release 发布 | 签名打包 → 传 Release → 镜像到 LSPosed 仓库 |

镜像用的 tag 由 `build.gradle.kts` 里的 `versionCode` / `versionName` 拼出（`<code>-<name>`）；
需要 `SIGNING_KEYSTORE` / `SIGNING_PASS`，镜像另需 `LSPOSED_REPO_TOKEN`（不填则跳过）。

> 仓库目前只有 `main`；nightly 那条要跑得先建 `dev` 分支。

## 许可

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)

- 功能 / 安装 / 用法：[README.md](README.md)
