// 腾讯云「实时语音识别」（wss://asr.cloud.tencent.com/asr/v2/<appid>?…）
//
// 与讯飞那条的三点不同：
//   · 鉴权在 **URL query** 上（HMAC-SHA1 → base64 → URL encode），请求头什么都不用带；
//   · 音频走**二进制帧**（ws.sendBinary），控制与结果走文本帧；
//   · 结束发文本 {"type":"end"}，服务端最后一条带 final=1。
//
// 官方要点（local/plan.md §18.2）：
//   · 16k/16bit/单声道、voice_format=1（pcm）、建议 200ms 一包（16k ⇒ 6400B）；
//   · 发送间隔 >6s、或长期快于 1:1 实时率会被断；单分片 60s 上限；
//   · needvad=1 时服务端按 VAD 切片，结果带 slice_type（0 开始 / 1 非稳态 / 2 稳态）
//     与 index，要按 index 重拼。
//
// ⚠️ 脚本版本 1：**尚未联调**（需要真实 SecretId / SecretKey，见 local/plan.md §18.5）。
//    出错时的口径照旧：把服务端返回的 JSON **原样**交给宿主上屏。
engine.id = "builtin-tencent";
engine.label = "腾讯云实时语音识别";
engine.hosts = ["asr.cloud.tencent.com"];

engine.input = {};
engine.input.appid = "";
engine.input.secretId = "";
engine.input.secretKey = "";
engine.input.engineModelType = "16k_zh";

var HOST = "asr.cloud.tencent.com";
var PATH = "/asr/v2/";
var SLICE_BYTES = 6400;                 // 200ms @16k/16bit/mono

engine.start = function (c) {
    var self = this;
    this.c = c;
    this.parts = {};                    // index -> 该分片文本
    this.queue = [];                    // 攒到 200ms 再发
    this.queued = 0;
    this.done = false;
    this._ws = null;

    var cfg = c.config || {};
    var miss = [];
    if (!cfg.appid) miss.push("AppID");
    if (!cfg.secretId) miss.push("SecretId");
    if (!cfg.secretKey) miss.push("SecretKey");
    if (miss.length) {
        c.fail("CONFIG", "缺少 " + miss.join(" / ") + " —— 请单击这张卡片填写");
        return;
    }

    var url = sign(c, cfg);
    c.log("tencent: connecting " + HOST);
    var ws = c.ws(url);
    this._ws = ws;

    ws.onOpen(function () {
        c.log("tencent: open（音频按 200ms 一包发）");
    });
    ws.onMessage(function (t) {
        onMessage(self, t);
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
    if (this.queued >= SLICE_BYTES) flush(this, false);
};

engine.stop = function () {
    if (this.done || !this.c) return;
    flush(this, true);                  // 尾包（不足 200ms 也发出去）
    if (this._ws) this._ws.sendText(JSON.stringify({ type: "end" }));
};

/** 把攒下的帧拼成 200ms 一包发出去（二进制帧）。 */
function flush(self, last) {
    if (self.queued <= 0) return;
    var merged = self.c.concat(self.queue);
    self.queue = [];
    self.queued = 0;
    if (self._ws) self._ws.sendBinary(merged);
}

/**
 * 鉴权：原文 = `asr.cloud.tencent.com/asr/v2/<appid>?<参数按字典序>`（**不含 wss://**），
 * signature = Base64(HMAC-SHA1(原文, SecretKey))，再 URL encode 拼回 URL。
 * 注意 ctx.hmacSha1 返回的就是 base64（见 ScriptEngine.mac）。
 */
function sign(c, cfg) {
    var ts = Math.floor(c.now() / 1000);
    var params = {
        engine_model_type: cfg.engineModelType || "16k_zh",
        expired: ts + 3600,
        needvad: cfg.needvad === "0" ? 0 : 1,
        nonce: Math.floor(Math.random() * 1000000000),
        secretid: cfg.secretId,
        timestamp: ts,
        voice_format: 1,
        voice_id: c.uuid()
    };
    var keys = Object.keys(params).sort();
    var pairs = [];
    for (var i = 0; i < keys.length; i++) pairs.push(keys[i] + "=" + params[keys[i]]);
    var original = HOST + PATH + cfg.appid + "?" + pairs.join("&");
    var sig = c.hmacSha1(cfg.secretKey, original);
    pairs.push("signature=" + encodeURIComponent(sig));
    return "wss://" + HOST + PATH + cfg.appid + "?" + pairs.join("&");
}

function onMessage(self, t) {
    var m;
    try {
        m = JSON.parse(t);
    } catch (e) {
        self.c.fail("PARSE", "服务端返回无法解析为 JSON：" + t);
        return;
    }
    if (m.code !== 0) {
        // 原样上屏（code / message / sid 都在里面），别只取 message 把上下文丢了
        self.c.fail("TENCENT", t);
        return;
    }
    var r = m.result || {};
    var text = r.voice_text_str !== undefined ? r.voice_text_str : "";
    var idx = r.index !== undefined ? r.index : 0;
    var st = r.slice_type !== undefined ? r.slice_type : 0;
    if (st === 0 && idx === 0 && hasParts(self.parts)) self.parts = {};   // 新一轮（VAD 重新开始）
    self.parts[idx] = text;
    var full = joinParts(self.parts);
    if (m.final === 1 || r.final === 1) {
        self.done = true;
        self.c.finalText(full, 0.0);
        if (self._ws) self._ws.close();
    } else if (full) {
        self.c.partial(full);
    }
}

function hasParts(parts) {
    for (var k in parts) {
        if (parts.hasOwnProperty(k)) return true;
    }
    return false;
}

/** 按 index 从小到大拼全文（slice_type=0/1/2 都是覆盖同一个 index）。 */
function joinParts(parts) {
    var keys = [];
    for (var k in parts) {
        if (parts.hasOwnProperty(k)) keys.push(parseInt(k, 10));
    }
    keys.sort(function (a, b) { return a - b; });
    var out = "";
    for (var i = 0; i < keys.length; i++) out += parts[keys[i]];
    return out;
}
