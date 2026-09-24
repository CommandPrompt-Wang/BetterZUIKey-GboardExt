// 离线语音：Paraformer-zh-small（sherpa-onnx，int8 82MB）—— **完全不联网**
//
// 与云端引擎的区别：
//   · engine.hosts 为空（宿主会强制校验：空名单 = 一个域名都不许连）；
//   · 非流式模型 ⇒ 录音期间出不了部分结果（只有波形），停止后一次性解码上屏；
//   · 识别跑在 Gboard 进程里（宿主原语 ctx.localAsr），模型由设置页下载、经 provider 交给注入侧。
//
// 脚本版本：1（2026-09-24 首版）
engine.id = "builtin-paraformer";
engine.label = "Paraformer（离线）";
engine.hosts = [];

engine.input = {};        // 无需任何参数

var frames = [];          // 录到的 PCM 帧（非流式：攒到 stop 一起解）

engine.start = function (c) {
    this.c = c;
    frames = [];
    c.localAsrPreload("paraformer");   // 预热：模型加载要几秒，趁用户还在说话时加载好
    c.log("offline: Paraformer，本地解码（录音中不出部分结果）");
};

engine.audio = function (f) {
    frames.push(f);       // 只存引用，不拷贝
};

engine.stop = function () {
    var c = this.c;
    if (!c) return;
    this.c = null;
    var pending = frames;      // 先取出来，再清空（顺序反了就解不到东西）
    frames = [];
    if (!pending.length) return;
    var t0 = c.now();
    var text = c.localAsr("paraformer", pending);
    c.log("offline: 解码 " + pending.length + " 帧，用时 " + (c.now() - t0) + "ms");
    if (text) c.finalText(text, 0.0);   // 空串 = 宿主已经报错收尾了，别再上屏
};
