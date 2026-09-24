// 离线语音：Zipformer 流式（中英双语，sherpa-onnx 流式 transducer，int8 189MiB）
// —— **完全不联网、边说边出字**
//
// 与另两档（SenseVoice / Paraformer）的区别：
//   · 这一档**自己就是流式**：每 40ms 喂一帧就能拿到"到目前为止的全文"，不需要 VAD 切分
//     （所以「启用切分」开关对它无效，留着不置灰，用户想开就开）；
//   · 不带标点 ⇒ 想带标点请在设置页开「补全标点」（离线标点模型，需下载）；
//   · 识别跑在 Gboard 进程里（宿主原语 ctx.localAsrStream*），模型由设置页下载、经 provider 交付。
//
// 上屏口径（见 local/plan.md §28.4）：会话中的部分结果走结果通道（ctx.partial），
// 停止后的最终结果由宿主用**输入连接直接提交**（宿主一看是离线识别就会这么做）。
//
// 脚本版本：1
engine.id = "builtin-zipformer-bi";
engine.label = "Zipformer 流式（中英，离线）";
engine.hosts = [];

engine.input = {};        // 无需任何参数

var MODEL = "zipformer-bi";
var last = "";            // 上次已经发出去的部分结果（不变就不重复发，省得白刷 Gboard）

engine.start = function (c) {
    this.c = c;
    last = "";
    c.localAsrPreload(MODEL);        // 预热：流式权重也要加载时间，趁用户说话时load好
    c.localAsrStreamStart(MODEL);
    c.log("offline: " + MODEL + "（流式，边说边出字）");
};

engine.audio = function (f) {
    var c = this.c;
    if (!c) return;
    var text = c.localAsrStreamFeed(MODEL, f);   // 返回"到目前为止的全文"
    if (text && text !== last) {
        last = text;
        c.partial(text);
    }
};

engine.stop = function () {
    var c = this.c;
    if (!c) return;
    this.c = null;
    var text = c.localAsrStreamFinish(MODEL);
    last = "";
    if (text) c.finalText(text, 0.0);            // 空串 = 宿主已经报错收尾了，别再上屏
};
