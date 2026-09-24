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
// 流程：全客户端请求（JSON 参数，`result_type=full`）→ 逐包纯音频 → 最后一包 flags=0b0010。
// 响应同样是二进制帧、payload 是 JSON；**我们要求不压缩**（压缩就要再加 gunzip 原语）。
//
// 鉴权：**旧版控制台**的 App ID + Access Token（请求头 X-Api-App-Key + X-Api-Access-Key）。
// 新版控制台（单个 API Key）是另一个配置文件：`volc.js`。
// Resource-Id 决定"哪个模型 + 怎么计费"（四个取值：2.0 小时版 volc.seedasr.sauc.duration /
// 2.0 并发版 volc.seedasr.sauc.concurrent / 1.0 小时版 volc.bigasr.sauc.duration /
// 1.0 并发版 volc.bigasr.sauc.concurrent）；端点只决定"怎么交互"，与版本无关。
engine.id = "builtin-volc-legacy";
engine.label = "豆包流式语音识别（旧版）";
engine.hosts = ["openspeech.bytedance.com"];

engine.input = {};
var DEFAULT_RESOURCE = "volc.seedasr.sauc.duration";      // 2.0 小时版
var DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";

engine.input.appId = "";                  // 旧版控制台的 App ID
engine.input.accessToken = "";            // 旧版控制台的 Access Token
engine.input.resourceId = DEFAULT_RESOURCE;
// 接入点（默认双向流式）。要换区域/换 `_async` 等端点时改这里 —— 也是本地联调（假服务端）用的开关。
engine.input.enableNonstream = "false";   // 二次识别修正（默认关）
engine.input.enableItn = "false";         // 规范为书面格式（默认关）
engine.input.enableDdc = "false";         // 语义顺滑（默认关）

var HOST = "openspeech.bytedance.com";
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
    this.stopping = false;              // 用户已按停：之后的 onClose/onError 不再当失败报
    this.ready = false;                 // 全客户端请求发出去了才算 ready（音频不能抢跑）
    this.last = "";                     // 最后一次非空全文（收尾帧若不带内容，用它兜底）
    this.sent = "";                     // 上一次已经上屏的部分结果（不变就别重复发）
    this._ws = null;

    var cfg = c.config || {};
    var headers = {
        "X-Api-Resource-Id": cfg.resourceId || DEFAULT_RESOURCE,
        "X-Api-Request-Id": c.uuid(),
        "X-Api-Sequence": "-1"
    };
    if (!cfg.appId || !cfg.accessToken) {
        c.fail("CONFIG", "还没填 App ID / Access Token —— 单击这张卡片填一下");
        return;
    }
    headers["X-Api-App-Key"] = cfg.appId;
    headers["X-Api-Access-Key"] = cfg.accessToken;
    c.log("volc: 鉴权用旧版 App ID + Access Token，Resource-Id="
            + headers["X-Api-Resource-Id"]);
    // 这条接口的地址是**唯一**的（官方文档只给了 bigmodel_async）⇒ 不再让配置覆盖它
    c.log("volc: connecting " + DEFAULT_ENDPOINT);
    var ws = c.ws(DEFAULT_ENDPOINT, headers);
    this._ws = ws;

    ws.onOpen(function () {
        c.log("volc: open（发全客户端请求）");
        sendRequest(self);
        self.ready = true;              // 顺序要紧：**先请求、后音频**（见下）
        if (self.queued >= SLICE_BYTES) sendAudio(self, false);
    });
    ws.onBinary(function (b) {
        onFrame(self, b);
    });
    ws.onError(function (e) {
        if (self.done || self.stopping) return;
        var s = String(e);
        // 握手 403 的正文会告诉我们原因；最常见的是"这个 Resource 没开通"
        if (s.indexOf("not granted") >= 0 || s.indexOf("resource_id") >= 0) {
            c.fail("VOLC", "这个模型版本没有开通 —— 请在火山控制台「服务管理」里确认，"
                + "并把卡片里的「模型版本」改成你开通的那个");
            return;
        }
        c.fail("NET", s);
    });
    ws.onClose(function (info) {
        // 收尾时宿主会主动关连接 ⇒ 别再报"失败"（否则弹 toast 还把"出现错误…"当最终文本上屏）
        if (self.done || self.stopping) return;
        c.fail("NET", "连接已关闭，且没有收到最终结果：" + info);
    });
};

engine.audio = function (f) {
    if (this.done || !this.c) return;
    this.queue.push(f);
    this.queued += f.length;
    // **握手期间只攒不发**：踩过 —— 音频先于"全客户端请求"到达，服务端直接断连
    // （日志：ws: flushed N 帧 → 紧接着 volc: open → EOF，没有 close 帧）
    if (!this.ready) return;
    if (this.queued >= SLICE_BYTES) sendAudio(this, false);
};

engine.stop = function () {
    if (this.done || !this.c) return;
    this.stopping = true;
    sendAudio(this, true);              // 最后一包带 flags=0b0010
};

/** 全客户端请求：JSON 参数（序列化=JSON，压缩=无）。 */
function sendRequest(self) {
    var cfg = self.c.config || {};          // 开关在配置里（sendRequest 里没有 cfg，得自己取）
    // 请求体严格照官方文档（双向流式）：顶层只有 audio + request；
    // 早先多传的 `user` 与 `audio.language` 文档里没有（已去掉，避免被严格校验拒掉）
    var body = {
        audio: {
            format: "pcm",
            rate: 16000,
            bits: 16,
            channel: 1,
            codec: "raw"
        },
        request: {
            model_name: "bigmodel",
            enable_itn: cfg.enableItn === "true",     // 规范为书面格式
            enable_punc: true,
            // full = 服务端每次都回"目前为止的全文"（增量模式 single 回的是分句，
            // 拼接不当会让屏幕上已显示的字被"撤回"——实测踩过）
            result_type: "full",
            // 两个可选优化（表单里是开关；字符串 "true" 才算开）
            enable_nonstream: cfg.enableNonstream === "true",
            enable_ddc: cfg.enableDdc === "true"
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
    var full = textOf(self, m);
    if (full) self.last = full;
    var last = (flags & FLAG_LAST) !== 0 || m.is_last_package === true
        || (m.sequence !== undefined && m.sequence < 0);
    if (last) {
        self.done = true;
        self.c.finalText(full || self.last, 0.0);      // 收尾帧不带内容 ⇒ 用最后一次全文兜底
        if (self._ws) self._ws.close();
    } else if (full && full !== self.sent) {
        self.sent = full;
        self.c.partial(full);
    }
}

/**
 * 取文本：**只增不减**。
 *
 * <p>为什么：服务端每条响应的 `utterances` 只是"它当前认为的这批"，早先的句子可能不再出现
 * （实测：`"好，我们接着再往下。耶…线网"` → 下一条直接变成 `"耶…线网类型的"`，第一句没了，
 * 屏幕上看起来就是"字被撤回"）。所以：
 *   · `result_type=full` 时优先用服务端给的全文 `result.text`；
 *   · 否则把 utterances 拼起来，两者取长的那个；
 *   · 最后再用 `self.last` 兜一道：**显示文本永不缩短**（定稿过的内容不再消失）。
 *   （真正的"最终结果"仍以服务端最后一条为准，见 onFrame 的 last 分支。）
 */
function textOf(self, m) {
    var r = m.result || {};
    var full = r.text !== undefined ? r.text : "";
    var utts = r.utterances || [];
    var joined = "";
    for (var i = 0; i < utts.length; i++) {
        var u = utts[i] || {};
        joined += u.text !== undefined ? u.text : "";
    }
    var cand = full.length >= joined.length ? full : joined;
    return cand.length >= self.last.length ? cand : self.last;   // 不撤回
}
