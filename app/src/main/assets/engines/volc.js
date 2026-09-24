// 火山引擎（豆包）流式语音识别 v3 —— wss://openspeech.bytedance.com/api/v3/sauc/bigmodel
//
// 与另两家的区别：**整个协议都是自定义二进制帧**（所以宿主补了 ws.sendBinary / onBinary
// 与 ctx.bytes/bytesOf/concat，见 local/plan.md §18.3）。
//
// 帧格式（§18.2）：
//   [4B header][4B payload size（大端）][payload]
//   header.byte0 = 协议版本(高 4 位，固定 0b0001) | 头大小(低 4 位，固定 0b0001 ⇒ 4 字节)
//   header.byte1 = 消息类型(高 4 位) | 消息标志(低 4 位)
//   header.byte2 = 序列化(高 4 位) | 压缩(低 4 位)
//   header.byte3 = 保留 0x00
//   消息类型：0b0001 全客户端请求 / 0b0010 纯音频 / 0b1001 服务端响应 / 0b1011 错误
//   消息标志：0b0000 无序号 / 0b0001 正序号 / 0b0010 最后一包 / 0b0011 最后一包（负序号）
//   序列化：0b0001 JSON / 0b0000 无；压缩：0b0000 不压缩 / 0b0001 gzip
//
// 流程：全客户端请求（JSON 参数，`result_type=single`）→ 逐包纯音频 → 最后一包 flags=0b0010。
// 响应同样是二进制帧、payload 是 JSON；**我们要求不压缩**（压缩就要再加 gunzip 原语）。
//
// ⚠️ 脚本版本 1：**尚未联调**（需要火山控制台的 App ID / Access Token / Resource-Id）。
//    鉴权头用「旧控制台」那套（X-Api-App-Key + X-Api-Access-Key）；新版单 key 的话把
//    accessKey 留空、appKey 填 X-Api-Key 即可（见 §18.2 的对照表）。
engine.id = "builtin-volc";
engine.label = "豆包流式语音识别（火山）";
engine.hosts = ["openspeech.bytedance.com"];

engine.input = {};
engine.input.appKey = "";
engine.input.accessKey = "";
engine.input.resourceId = "volc.bigasr.sauc.duration";
// 接入点（默认双向流式）。要换区域/换 `_async` 等端点时改这里 —— 也是本地联调（假服务端）用的开关。
engine.input.endpoint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel";

var HOST = "openspeech.bytedance.com";
var URL = "wss://" + HOST + "/api/v3/sauc/bigmodel";
var SLICE_BYTES = 6400;                 // 200ms @16k/16bit/mono

var MSG_FULL_REQUEST = 1;
var MSG_AUDIO_ONLY = 2;
var MSG_SERVER_RESPONSE = 9;
var MSG_SERVER_ERROR = 11;

var FLAG_NONE = 0;
var FLAG_LAST = 2;                      // 0b0010：最后一包

engine.start = function (c) {
    var self = this;
    this.c = c;
    this.queue = [];
    this.queued = 0;
    this.done = false;
    this._ws = null;

    var cfg = c.config || {};
    var miss = [];
    if (!cfg.appKey) miss.push("App ID");
    if (!cfg.accessKey) miss.push("Access Token");
    if (miss.length) {
        c.fail("CONFIG", "缺少 " + miss.join(" / ") + " —— 请单击这张卡片填写");
        return;
    }

    var headers = {
        "X-Api-App-Key": cfg.appKey,
        "X-Api-Access-Key": cfg.accessKey,
        "X-Api-Resource-Id": cfg.resourceId || "volc.bigasr.sauc.duration",
        "X-Api-Request-Id": c.uuid(),
        "X-Api-Sequence": "-1"
    };
    c.log("volc: connecting " + HOST);
    var ws = c.ws(URL, headers);
    this._ws = ws;

    ws.onOpen(function () {
        c.log("volc: open（发全客户端请求）");
        sendRequest(self);
    });
    ws.onBinary(function (b) {
        onFrame(self, b);
    });
    ws.onError(function (e) {
        if (!self.done) c.fail("NET", String(e));
    });
    ws.onClose(function (info) {
        if (!self.done) c.fail("NET", "连接已关闭，且没有收到最终结果：" + info);
    });
};

engine.audio = function (f) {
    if (this.done || !this.c) return;
    this.queue.push(f);
    this.queued += f.length;
    if (this.queued >= SLICE_BYTES) sendAudio(this, false);
};

engine.stop = function () {
    if (this.done || !this.c) return;
    sendAudio(this, true);              // 最后一包带 flags=0b0010
};

/** 全客户端请求：JSON 参数（序列化=JSON，压缩=无）。 */
function sendRequest(self) {
    var lang = self.c.session && self.c.session.languageTag
        ? self.c.session.languageTag : "zh-CN";
    var body = {
        user: { uid: "bzk-gboardext" },
        audio: {
            format: "pcm",
            rate: 16000,
            bits: 16,
            channel: 1,
            codec: "raw",
            language: lang
        },
        request: {
            model_name: "bigmodel",
            enable_itn: true,
            enable_punc: true,
            result_type: "single"       // single=增量（按 utterances 的 definite 重拼）
        }
    };
    var payload = self.c.bytesOf(JSON.stringify(body));
    if (self._ws) self._ws.sendBinary(frame(self.c, MSG_FULL_REQUEST, FLAG_NONE, 1, payload));
}

/** 纯音频包（序列化=无、压缩=无）；最后一包 flags=0b0010。 */
function sendAudio(self, last) {
    if (self.queued <= 0 && !last) return;
    var payload = self.queued > 0 ? self.c.concat(self.queue) : self.c.bytes(0);
    self.queue = [];
    self.queued = 0;
    if (self._ws) {
        self._ws.sendBinary(frame(self.c, MSG_AUDIO_ONLY, last ? FLAG_LAST : FLAG_NONE, 0, payload));
    }
}

/** 拼一个协议帧：[4B header][4B payload size（大端）][payload]。 */
function frame(c, msgType, flags, serialization, payload) {
    var head = c.bytes(8);
    head.setU8(0, 0x11);                                   // 版本 1 + 头大小 4
    head.setU8(1, (msgType << 4) | flags);
    head.setU8(2, (serialization << 4) | 0);               // 不压缩
    head.setU8(3, 0x00);
    head.setU32be(4, payload.length);
    return c.concat([head, payload]);
}

/** 解析服务端帧：同样的头 + 4B 长度 + JSON（若带序号，长度前还有 4B 序号）。 */
function onFrame(self, b) {
    if (self.done) return;
    var msgType = (b.u8(1) >> 4) & 0x0f;
    var flags = b.u8(1) & 0x0f;
    var compression = b.u8(2) & 0x0f;
    var off = 4;
    if (flags & 0x1) off += 4;                             // 带序号
    var size = b.u32be(off);
    var json = b.str(off + 4, size);
    if (compression !== 0) {
        // 我们请求的是不压缩；服务端真压了就只能报出来（宿主没给 gunzip 原语）
        self.c.fail("VOLC", "服务端返回了压缩帧（compression=" + compression + "）：" + json);
        return;
    }
    if (msgType === MSG_SERVER_ERROR) {
        self.c.fail("VOLC", json);                         // 原样上屏（含 code/message）
        return;
    }
    if (msgType !== MSG_SERVER_RESPONSE) return;           // 其余（如 ack）忽略

    var m;
    try {
        m = JSON.parse(json);
    } catch (e) {
        self.c.fail("PARSE", "服务端返回无法解析为 JSON：" + json);
        return;
    }
    var full = textOf(m);
    var last = (flags & FLAG_LAST) !== 0 || m.is_last_package === true
        || (m.sequence !== undefined && m.sequence < 0);
    if (last) {
        self.done = true;
        self.c.finalText(full, 0.0);
        if (self._ws) self._ws.close();
    } else if (full) {
        self.c.partial(full);
    }
}

/**
 * 取文本。`result_type=single`（增量）返回的是**分句**结果：definite=true 的句子是定稿，
 * 其余是当前句的临时结果 —— 按官方口径拼成全文。
 */
function textOf(m) {
    var r = m.result || {};
    var utts = r.utterances || [];
    if (utts.length === 0) return r.text !== undefined ? r.text : "";
    var stable = "";
    var tail = "";
    for (var i = 0; i < utts.length; i++) {
        var u = utts[i] || {};
        var t = u.text !== undefined ? u.text : "";
        if (u.definite === true) stable += t;
        else tail = t;                                     // 增量只保留最后一句临时结果
    }
    return stable + tail;
}
