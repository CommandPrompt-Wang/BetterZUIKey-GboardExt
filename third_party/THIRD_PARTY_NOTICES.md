# 第三方组件与模型许可

本模块（`moe.lovefirefly.bzk.gboardext`）分发或使用了下列第三方组件与模型。本文件与
`licenses/` 下的许可全文位于仓库 `third_party/` 目录，并通过 Gradle 的 `assets.srcDirs`
**原样**打进 APK（不复制，避免两份漂移），设置页「关于 → 开源许可」直接显示它们。

## 一、随 APK 分发的组件

| 组件 | 版本 | 许可 | 形式 / 用途 |
| --- | --- | --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 1.13.8 | Apache-2.0 | 离线语音识别运行时：`jniLibs/arm64-v8a/libsherpa-onnx-jni.so`；`com/k2fsa/sherpa/onnx/` 下的 Java API（取自同版本 tag，未改逻辑） |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | 1.28.2（随 sherpa-onnx 的 AAR） | MIT | 推理后端：`jniLibs/arm64-v8a/libonnxruntime.so` |
| [silero-vad](https://github.com/snakers4/silero-vad) | 导出件由 sherpa-onnx 发布 | MIT | VAD 模型：`assets/models/silero_vad.onnx`（句子切分） |
| [DexKit](https://github.com/LuckyPray/DexKit) | 2.2.0 | Apache-2.0 / LGPL-3.0 | 运行期按结构定位 Gboard 的混淆类与回调（本项目按 Apache-2.0 使用） |
| [Mozilla Rhino](https://github.com/mozilla/rhino) | 1.7.15 | MPL-2.0 | 语音引擎脚本宿主（解释执行模式） |
| [libxposed API](https://github.com/LibXposed/api) | 101.0.0 | Apache-2.0 | LSPosed 模块接口（编译期依赖，运行期由框架提供） |
| [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx) | 1.1.0 | Apache-2.0 | 设置页界面 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.10.0 | Apache-2.0 | 设置页界面 |

## 二、由用户从上游下载的模型（不随 APK 分发）

「离线语音」里的模型由**用户自行下载**并保存在设备上；本模块只负责传输与推理调用，
**不重新分发**权重文件。各模型仍受其上游许可约束：

| 模型 | 上游 | 许可 | 署名 / 备注 |
| --- | --- | --- | --- |
| SenseVoice-Small（int8） | [FunAudioLLM/SenseVoice](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) → [sherpa-onnx 导出件](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) | **FunASR Model Open Source License 1.1** | 该许可 §2.2 要求署名来源与作者并**保留模型名**：本模块在设置页与日志里均以 `SenseVoice-Small`（及上游仓库名）原样呈现，未改名 |
| Paraformer-zh-small（int8） | [sherpa-onnx 导出件](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09)（源自 ModelScope / FunASR） | FunASR 系列模型许可 | 同样保留模型名 `Paraformer-Small` |
| streaming zipformer zh-14M | [sherpa-onnx 导出件](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23) | Apache-2.0 | icefall / k2-fsa 训练 |
| streaming zipformer bilingual-zh-en | [sherpa-onnx 导出件](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20) | Apache-2.0 | 同上 |
| 标点模型（中英 CT-Transformer，int8） | [k2-fsa/sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models) | 见上游发布页 | 训练方 k2-fsa |

FunASR 模型许可的约束对象是**模型权重**（其代码库 FunASR 为 MIT）；原文见
`licenses/LICENSE-FunASR-Model.txt`。

## 三、本模块自身

GPL-3.0，全文见仓库根目录 `LICENSE`。

---

许可全文索引（同目录 `licenses/`）：

| 文件 | 对应 |
| --- | --- |
| `LICENSE-Apache-2.0.txt` | sherpa-onnx · DexKit · libxposed API · AndroidX · Material |
| `LICENSE-MIT-onnxruntime.txt` | ONNX Runtime |
| `LICENSE-MIT-silero-vad.txt` | silero-vad |
| `LICENSE-MPL-2.0.txt` + `NOTICE-rhino.txt` | Mozilla Rhino |
| `LICENSE-FunASR-Model.txt` | SenseVoice / Paraformer 权重 |
