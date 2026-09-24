// 离线语音：SenseVoice-Small（sherpa-onnx，int8 228MB）—— **完全不联网**
//
// 与云端引擎的区别：
//   · engine.hosts 为空（宿主会强制校验：空名单 = 一个域名都不许连）；
//   · 非流式模型 ⇒ 录音期间出不了部分结果（只有波形），停止后一次性解码上屏；
//   · 识别跑在 Gboard 进程里（宿主原语 ctx.localAsr），模型由设置页下载、经 provider 交给注入侧。
//
// 脚本版本：2（2026-09-24 支持 VAD 模拟流式：开关开启时边切边出字）
engine.id = "builtin-sensevoice";
engine.label = "SenseVoice（离线）";
engine.hosts = [];

engine.input = {};        // 无需任何参数

var MODEL = "sensevoice";
var frames = [];          // 切分关闭时：攒到 stop 一次解码

engine.start = function (c) {
    this.c = c;
    frames = [];
    c.localAsrPreload(MODEL);                 // 预热：模型加载要几秒，趁说话时load好
    if (c.session.vad) c.localAsrStart(MODEL); // 切分开启：开一条"模拟流式"会话
    c.log("offline: " + MODEL + "（切分=" + (c.session.vad ? "开" : "关") + "）");
};

engine.audio = function (f) {
    var c = this.c;
    if (!c) return;
    if (c.session.vad) {
        var full = c.localAsrFeed(MODEL, f);   // 每完成一句就返回全文
        if (full) c.partial(full);             // 边说边出字
    } else {
        frames.push(f);                        // 只存引用，不拷贝
    }
};

engine.stop = function () {
    var c = this.c;
    if (!c) return;
    this.c = null;
    var text = c.session.vad ? c.localAsrFinish(MODEL) : c.localAsr(MODEL, frames);
    frames = [];
    if (text) c.finalText(text, 0.0);          // 空串 = 宿主已经报错收尾了，别再上屏
};
