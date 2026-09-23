// 讯飞开放平台「语音听写（流式版）」—— WebSocket + HMAC-SHA256 鉴权
//
// 用法：把下面的 engine.config 填上你自己的三个值（讯飞控制台 → 我的应用 → 语音听写（流式版）），
//       然后长按这张卡片复制代码、粘回「添加配置文件」导入即可（engine.id 不变 ⇒ 覆盖原配置）。
//
// 接口要点（照官方文档）：
//   · wss://iat-api.xfyun.cn/v2/iat?authorization=…&date=…&host=…
//   · 音频 16k/16bit/单声道 PCM，**每次 1280B / 40ms**（正好是宿主给的帧长）
//   · 首帧带 common/business，data.status：0=第一帧 1=中间帧 2=最后一帧
//   · 开了动态修正（dwa=wpgs）后：pgs=apd 追加 / pgs=rpl 表示要替换结果序号区间 rg
//   · 会话上限 60s；超过 10s 不发数据服务端会断开
//
// 脚本约束：只用 ES5；只认宿主注入的 ctx（没有 java/Packages/load）。
//
// 脚本版本：3（2026-09-24 声明 engine.hosts 白名单；出错时把服务端原文/异常原样交给宿主上屏）
//   改这个文件后**不需要**用户手动点「恢复内置配置」：用户没改过的内置脚本会随模块自动更新
//   （见 VoiceProfiles.syncBuiltins；改过的会保留并在子页标「已修改」）。

engine.id = "builtin-xunfei";
engine.label = "讯飞语音听写";

if (typeof engine.input !== "object") engine.input = {};   // 宿主会预建；这行是自保

// 这三项会出现在「单击卡片」的表单里（engine.input.*）
// 顺序照讯飞控制台的展示顺序：APPID → APISecret → APIKey
engine.input.appid = "";
engine.input.apiSecret = "";
engine.input.apiKey = "";

// 允许脚本连的域名（**宿主强制校验**：不在名单里的 ws 连接会被直接掐掉并把原因上屏）。
// 内置项的名单以 index.json 的 hosts 为准；用户导入的脚本靠这一行"自带白名单"，
// 也可以在设置页（单击卡片）里改。
engine.hosts = ["iat-api.xfyun.cn"];

var HOST = "iat-api.xfyun.cn";
var PATH = "/v2/iat";

engine.start = function (c) {
    var self = this;
    this.c = c;
    this.parts = {};      // sn -> 该片文本（动态修正要靠它重拼）
    this.sn = 0;
    this.audioSeq = 0;
    this.done = false;

    var cfg = c.config || {};
    var miss = [];
    if (!cfg.appid) miss.push("APPID");
    if (!cfg.apiSecret) miss.push("APISecret");
    if (!cfg.apiKey) miss.push("APIKey");
    if (miss.length) {
        c.fail("CONFIG", "缺少 " + miss.join(" / ") + " —— 请单击这张卡片填写");
        return;
    }

    // **首帧必须由"第一段音频"带出去**（common/business + status=0）。
    // 不能在 onOpen 里才置位：音频帧会在握手期间就排队，那时标志还没置上 ⇒ 排出去的全是裸 data 帧
    // ⇒ 服务端建不起会话（踩过：10165 invalid handle，而且表现成"丢字"）
    this.needHeader = true;
    this.audioSeq = 0;

    var url = sign(c, cfg);
    c.log("xunfei: connecting " + HOST);
    var ws = c.ws(url);
    this._ws = ws;                 // audio/stop 里要用

    ws.onOpen(function () {
        c.log("xunfei: open（首帧随第一段音频发）");
    });

    ws.onMessage(function (t) {
        var m;
        try { m = JSON.parse(t); } catch (e) {
            // 解析不了就把原文丢出去（拿到过 HTML 错误页/网关提示的场合）
            c.fail("PARSE", "服务端返回无法解析为 JSON：" + t);
            return;
        }
        if (m.code !== 0) {
            // **原样**给出服务端返回的 JSON（code/message/sid 都在里面）—— 用户口径：
            // "把原始信息、json 什么的直接打出来"，不要只取 message 把上下文丢了
            c.fail("IFLYTEK", t);
            return;
        }
        var d = m.data || {}, r = d.result;
        if (!r) return;

        var words = "";
        var wsArr = r.ws || [];
        for (var i = 0; i < wsArr.length; i++) {
            var cw = wsArr[i].cw || [];
            for (var j = 0; j < cw.length; j++) words += cw[j].w;
        }

        // 动态修正：rg 是"结果序号(sn)区间"，被替换的那几片丢掉，再按 sn 顺序重拼
        if (r.sn !== undefined) self.parts[r.sn] = words;
        if (r.pgs === "rpl" && r.rg) {
            for (var k = r.rg[0]; k <= r.rg[1]; k++) delete self.parts[k];
        }
        var full = "";
        var keys = [];
        for (var key in self.parts) {
            if (self.parts.hasOwnProperty(key)) keys.push(parseInt(key, 10));
        }
        keys.sort(function (a, b) { return a - b; });
        for (var x = 0; x < keys.length; x++) full += self.parts[keys[x]];

        if (d.status === 2 || r.ls) {
            self.done = true;
            c.finalText(full, 0.0);
            ws.close();
        } else if (full) {
            c.partial(full);
        }
    });

    ws.onError(function (e) {
        if (!self.done) c.fail("NET", String(e));
    });

    // 连接被关掉但结果没出来：原来这条路径是**静默**的（用户只看到语音框自己关了、
    // 日志里也没有一行），现在把原始关闭原因打出来并结束听写
    ws.onClose(function (info) {
        if (!self.done) c.fail("NET", "连接已关闭，且没有收到最终结果：" + info);
    });
};

engine.audio = function (pcm) {
    if (this.done || !this.c) return;
    if (this.needHeader) {
        // 第一段音频：带 common + business + status=0
        this.needHeader = false;
        this.audioSeq = 1;
        this.wsSend({
            common: { app_id: (this.c.config || {}).appid },
            business: {
                language: "zh_cn",
                domain: "iat",
                accent: "mandarin",
                dwa: "wpgs",          // 动态修正（控制台需开通；没开通不报错、只是不生效）
                ptt: 1,
                vad_eos: 3000
            },
            data: {
                status: 0,
                format: "audio/L16;rate=16000",
                encoding: "raw",
                audio: this.c.b64(pcm)
            }
        });
        return;
    }
    this.wsSend({
        data: {
            status: 1,
            format: "audio/L16;rate=16000",
            encoding: "raw",
            audio: this.c.b64(pcm)
        }
    });
};

engine.stop = function () {
    if (this.done || !this.c) return;
    this.wsSend({ data: { status: 2 } });      // 结束帧：服务端据此给出最后一片结果
};

// 存一下 ws 句柄，供 audio/stop 用
engine._ws = null;
engine.wsSend = function (obj) {
    if (this._ws) this._ws.sendText(JSON.stringify(obj));
};

// ------------------------------------------------------------------ 鉴权

function sign(c, cfg) {
    // RFC1123（GMT）；服务端允许 ±300s 时钟偏移
    var date = new Date().toUTCString();
    var origin = "host: " + HOST + "\ndate: " + date + "\nGET " + PATH + " HTTP/1.1";
    var sig = c.hmacSha256(cfg.apiSecret, origin);          // 已经是 base64
    var authOrigin = 'api_key="' + cfg.apiKey + '", algorithm="hmac-sha256", '
        + 'headers="host date request-line", signature="' + sig + '"';
    var authorization = c.b64(authOrigin);
    return "wss://" + HOST + PATH
        + "?authorization=" + encodeURIComponent(authorization)
        + "&date=" + encodeURIComponent(date)
        + "&host=" + HOST;
}
