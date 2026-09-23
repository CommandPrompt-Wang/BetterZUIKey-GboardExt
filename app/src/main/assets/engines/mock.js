// 模拟引擎（离线自测）—— 不联网，用来验证：
//   klu 代理 → AudioSource 采音 → 脚本 → ctx.partial/finalText → aaeo/aaes → kls.h → 上屏
//
// 脚本约束：只用 ES5（var / function / JSON / Object.keys），Rhino 与将来的 QuickJS 都能跑。
// 宿主注入的唯一全局对象是 ctx；没有 java / Packages / load。
// 注意：engine.audio / engine.stop 不收参数 —— 需要 ctx 就在 start 里存下来。

engine.id = "builtin-mock";
engine.label = "测试";

engine.start = function (c) {
    this._c = c;
    this._frames = 0;
    this._finaled = false;
    this._last = "";
    c.log("mock: start lang=" + c.session.languageTag
        + " rate=" + c.session.sampleRate + " frame=" + c.session.frameBytes);

    var self = this;
    c.after(500,  function () { self._say("你好"); });
    c.after(1100, function () { self._say("你好，世界"); });
    c.after(1700, function () { self._finish(); });
};

engine._say = function (text) {
    if (this._finaled) return;
    this._last = text;
    this._c.partial(text);
};

engine._finish = function () {
    if (this._finaled) return;
    this._finaled = true;
    this._c.finalText(this._last + "。", 0.99);
};

engine.audio = function (pcm) {
    this._frames++;
    // 每秒报一次帧数，确认音频真的喂进来了（40ms 一帧 ⇒ 约 25 帧/秒）
    if (this._frames % 25 === 1) {
        this._c.log("mock: frames=" + this._frames + " bytes=" + pcm.length);
    }
};

engine.stop = function () {
    // 用户提前按停：把当前文本当最终结果发出去（§12-① 的时序问题先按"稳妥"处理）
    this._finish();
};
