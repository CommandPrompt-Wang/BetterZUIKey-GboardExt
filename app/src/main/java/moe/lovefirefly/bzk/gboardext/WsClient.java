package moe.lovefirefly.bzk.gboardext;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;

/**
 * 极简 WebSocket 客户端（RFC 6455 的客户端子集），只服务于语音引擎脚本。
 *
 * <p><b>为什么自己写</b>：Java 没有内置 WS；Gboard 进程里的 OkHttp 不能假设可用；
 * 引一个第三方库（Java-WebSocket 等）要为 60 秒一次的长连接多背一份依赖。
 * 我们只需要：握手 / 发文本 / 收文本 / 回 pong / 关闭 —— 约 250 行。
 *
 * <p><b>音频是 base64 文本发出去的</b>（各家流式 ASR 都这样），所以这里不做二进制分片优化。
 *
 * <p><b>线程</b>：{@link #connect} 内部起一个读线程，回调都从该线程出来；
 * 调用方（{@link ScriptEngine}）负责把回调再投递回引擎线程 —— Rhino 的 Context 不跨线程。
 */
final class WsClient {

    private static final String TAG = "GboardExt";

    /** 单帧上限（ASR 的 JSON 结果都很小；给 4MB 足够防内存被刷爆）。 */
    private static final int MAX_PAYLOAD = 4 << 20;

    interface Listener {
        void onOpen();

        void onText(String text);

        /** 二进制帧（腾讯/火山的音频与结果都是 0x2 帧，见 local/plan.md §18.2）。 */
        void onBinary(byte[] data);

        /** 出错时的**原始**信息（异常全文 / 服务端返回的头与 body）。 */
        void onError(String msg);

        /** 连接结束；{@code info} 是原始关闭原因（close 帧的 code/reason 或 EOF）。 */
        void onClosed(String info);
    }

    private final String url;
    private final Map<String, String> headers;
    private final Listener listener;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open;
    private volatile boolean closed;
    /** 关闭原因（原样给脚本）：close 帧的 code/reason，或 EOF / 本地关闭。 */
    private volatile String closeInfo = "未连接";

    /**
     * 握手期间的**待发队列**。
     *
     * <p>为什么必须有：TLS+升级握手要 0.1~2 秒（实测讯飞 1.6s），而用户按完 Alt+D 立刻就开始说话
     * —— 早到的音频帧不能丢（丢了就是"丢字"），但也不能直接写（会把帧插进 HTTP 升级响应里）。
     * 所以先排队，握手成功后按顺序补发。
     */
    /** 握手期间排队的帧：**要记住 opcode**（文本与二进制不能混，见 sendBinary）。 */
    private static final class Out {
        final int op;
        final byte[] data;

        Out(int op, byte[] data) {
            this.op = op;
            this.data = data;
        }
    }

    private final java.util.ArrayDeque<Out> pending = new java.util.ArrayDeque<>();
    private static final int PENDING_MAX_FRAMES = 300;      // ≈12 秒 @40ms（实测 TLS 握手走了 5 秒）

    WsClient(String url, Map<String, String> headers, Listener listener) {
        this.url = url;
        this.headers = headers == null ? new HashMap<>() : headers;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ 连接

    void connect() {
        final Thread t = new Thread(this::run, "bzk-voice-ws");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try {
            final URI uri = URI.create(url);
            final boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
            final String host = uri.getHost();
            final int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
            final String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    ? "/" : uri.getRawPath();
            final String query = uri.getRawQuery();
            final String target = query == null ? path : path + "?" + query;

            final Socket s;
            if (tls) {
                final SSLSocket ss = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
                ss.connect(new InetSocketAddress(host, port), 10_000);
                // SNI 必须显式给：不带 SNI 的话部分服务端（含 googleapis 系）直接拒绝握手
                final SSLParameters p = ss.getSSLParameters();
                p.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
                ss.setSSLParameters(p);
                ss.startHandshake();
                s = ss;
            } else {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 10_000);
            }
            s.setSoTimeout(0);
            socket = s;
            out = s.getOutputStream();
            final InputStream in = s.getInputStream();

            // ---- 握手 ----
            final byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            final String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            final StringBuilder req = new StringBuilder();
            req.append("GET ").append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host)
                    .append(port == (tls ? 443 : 80) ? "" : ":" + port).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    // 有些网关/WAF（火山那侧是 Tengine）对没有 UA 的升级请求直接 403
                    .append("User-Agent: BetterZUIKey-GboardExt/1.0 (Android)\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();

            final String head = readHeaders(in);
            if (head == null) {
                throw new java.io.IOException("握手无响应（服务端没回任何字节）");
            }
            final int status = parseStatus(head);
            if (status != 101) {
                // 把服务端给的原因**原样**带出去：讯飞鉴权失败会返回 401 + JSON（body 在头后面，
                // 得再捞一小段），HTTP 层的错误页也照打 —— 用户口径是"原始信息直接打出来"
                throw new java.io.IOException("握手失败 HTTP " + status + "\n"
                        + head.trim() + bodySnippet(s, in, head));
            }
            open = true;
            Log.i(TAG, "ws: connected " + host + " (tls=" + tls + ")");
            flushPending();
            listener.onOpen();

            // ---- 帧循环 ----
            readLoop(in);
        } catch (Throwable tr) {
            if (!closed) {
                Log.w(TAG, "ws: " + tr);
                listener.onError(raw(tr));      // 带完整异常（含 cause 链），不是只有 message
            }
        } finally {
            open = false;
            closeQuietly();
            if (!closed) {
                closed = true;
                listener.onClosed(closeInfo);
            }
        }
    }

    /**
     * 头读完之后的残留字节（HTTP 错误响应体）。只捞一小段：这里唯一的用途是
     * "把服务端说的话原样给用户看"，不是解析。读不到就返回空串。
     */
    /**
     * 读一小段响应体（HTTP 错误页 / 网关的 JSON 原因）。
     *
     * <p>踩过：只读一次、超时 400ms —— 火山那边 403 的正文（82B JSON，里面才是真正的原因）
     * 就慢那么一点，结果日志里只有响应头，白跑一趟。现在**按 Content-Length 读**、超时放宽。
     */
    private static String bodySnippet(Socket s, InputStream in, String head) {
        try {
            int want = 1024;
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)content-length:\\s*(\\d+)").matcher(head);
            if (m.find()) {
                try {
                    want = Math.min(1024, Math.max(1, Integer.parseInt(m.group(1))));
                } catch (Throwable ignored) {
                }
            }
            s.setSoTimeout(1500);               // 只影响这条已经失败的连接
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[512];
            while (bos.size() < want) {
                final int n = in.read(buf, 0, Math.min(buf.length, want - bos.size()));
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            s.setSoTimeout(0);
            final String body = bos.toString("UTF-8").trim();
            return body.isEmpty() ? "" : "\n" + body;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 异常 → 原始文本（连 cause 一起，很多底层错误的原因都在 cause 里）。 */
    private static String raw(Throwable tr) {
        final StringBuilder sb = new StringBuilder(String.valueOf(tr));
        Throwable c = tr.getCause();
        for (int i = 0; c != null && i < 5; i++, c = c.getCause()) {
            sb.append("\n  caused by: ").append(c);
        }
        return sb.toString();
    }

    /** 逐字节读到 {@code \r\n\r\n}：**不能**用 BufferedReader，它会多吞帧数据。 */
    private static String readHeaders(InputStream in) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int state = 0;
        while (bos.size() < 32 * 1024) {
            final int b = in.read();
            if (b < 0) return bos.size() == 0 ? null : bos.toString("UTF-8");
            bos.write(b);
            state = (state == 0 && b == '\r') ? 1
                    : (state == 1 && b == '\n') ? 2
                    : (state == 2 && b == '\r') ? 3
                    : (state == 3 && b == '\n') ? 4 : 0;
            if (state == 4) break;
        }
        return bos.toString("UTF-8");
    }

    private static int parseStatus(String head) {
        try {
            final String first = head.split("\r\n", 2)[0];
            final String[] parts = first.split(" ");
            return Integer.parseInt(parts[1]);
        } catch (Throwable tr) {
            return -1;
        }
    }

    private static String tail(String head) {
        final String[] lines = head.split("\r\n");
        return lines.length > 1 ? lines[lines.length - 1] : "";
    }

    private void readLoop(InputStream in) throws Exception {
        while (open) {
            final int b0 = in.read();
            if (b0 < 0) {
                closeInfo = "连接被对端断开（EOF，没有 close 帧）";
                break;
            }
            final int b1 = in.read();
            if (b1 < 0) {
                closeInfo = "连接被对端断开（帧头读到一半就 EOF）";
                break;
            }
            final boolean fin = (b0 & 0x80) != 0;
            final int opcode = b0 & 0x0f;
            final boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((long) in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len < 0 || len > MAX_PAYLOAD) throw new java.io.IOException("帧过大 " + len);
            final byte[] mask = new byte[4];
            if (masked) readFully(in, mask, 4);
            final byte[] payload = new byte[(int) len];
            readFully(in, payload, (int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case 0x1:   // text
                case 0x2:   // binary（腾讯/火山的结果都是二进制帧）
                case 0x0: { // continuation（服务端可能把一条消息分多帧 —— 讯飞文档专门提醒过）
                    // 0x0 续帧要按"首个数据帧的类型"分派，所以记住上一次的数据帧类型
                    final boolean binary = opcode == 0x2 || (opcode == 0x0 && lastDataBinary);
                    if (opcode != 0x0) lastDataBinary = binary;
                    if (fin) {
                        if (binary) listener.onBinary(payload);
                        else listener.onText(new String(payload, "UTF-8"));
                    } else {
                        // 累积到收完为止
                        final ByteArrayOutputStream acc = new ByteArrayOutputStream();
                        acc.write(payload);
                        byte[] rest;
                        do {
                            rest = readOneFrame(in);
                            if (rest == null) break;
                            acc.write(rest);
                        } while (!lastFrameFin);
                        if (binary) listener.onBinary(acc.toByteArray());
                        else listener.onText(acc.toString("UTF-8"));
                    }
                    break;
                }
                case 0x8:   // close
                    open = false;
                    closeInfo = describeClose(payload);
                    sendFrame(0x8, payload);
                    return;
                case 0x9:   // ping → pong
                    sendFrame(0xA, payload);
                    break;
                case 0xA:   // pong：忽略
                    break;
                default:
                    break;
            }
        }
    }

    private boolean lastFrameFin;
    /** 上一条数据帧是不是二进制（0x0 续帧要接着它分发）。 */
    private boolean lastDataBinary;

    /** 读一帧的 payload（用于续帧拼接），顺带记录 FIN。 */
    private byte[] readOneFrame(InputStream in) throws Exception {
        final int b0 = in.read();
        if (b0 < 0) return null;
        lastFrameFin = (b0 & 0x80) != 0;
        final int b1 = in.read();
        if (b1 < 0) return null;
        final boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        final byte[] mask = new byte[4];
        if (masked) readFully(in, mask, 4);
        final byte[] payload = new byte[(int) len];
        readFully(in, payload, (int) len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        return payload;
    }

    /** close 帧 → "code=… reason=…"（原样，不打码）。 */
    private static String describeClose(byte[] payload) throws Exception {
        if (payload == null || payload.length < 2) return "对端发了 close 帧（无状态码）";
        final int code = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        final String reason = payload.length > 2
                ? new String(payload, 2, payload.length - 2, "UTF-8") : "";
        return "对端发来 close 帧 code=" + code + (reason.isEmpty() ? "" : " reason=" + reason);
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            final int n = in.read(buf, off, len - off);
            if (n < 0) throw new java.io.EOFException("连接被关闭");
            off += n;
        }
    }

    // ------------------------------------------------------------------ 发送

    void sendText(String text) {
        try {
            send(0x1, text.getBytes("UTF-8"));
        } catch (Throwable tr) {
            // UTF-8 编码理论上不会失败；真失败也别静默
            listener.onError("发送失败: " + raw(tr));
        }
    }

    /**
     * 发二进制帧（腾讯的音频、火山的整个协议帧）。
     *
     * <p>与文本帧共用同一条排队逻辑（握手期间的音频不能直接写进升级响应里）；
     * **opcode 必须一起排**，否则补发时会把二进制当文本发出去。
     */
    void sendBinary(byte[] b) {
        if (b == null || b.length == 0) return;
        send(0x2, b);
    }

    private void send(int op, byte[] b) {
        if (!open) {
            synchronized (pending) {
                if (pending.size() < PENDING_MAX_FRAMES) pending.add(new Out(op, b));
            }
            return;
        }
        try {
            sendFrame(op, b);
        } catch (Throwable tr) {
            listener.onError("发送失败: " + raw(tr));
        }
    }

    /** 握手成功后把排队的数据帧按顺序补发。 */
    private void flushPending() {
        int n = 0;
        while (true) {
            final Out o;
            synchronized (pending) {
                o = pending.poll();
            }
            if (o == null) break;
            try {
                sendFrame(o.op, o.data);
                n++;
            } catch (Throwable tr) {
                listener.onError("补发失败: " + raw(tr));
                break;
            }
        }
        if (n > 0) Log.i(TAG, "ws: flushed " + n + " 帧（握手期间的音频）");
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            sendFrame(0x8, new byte[] { 0x03, (byte) 0xE8 });   // 1000 normal closure
        } catch (Throwable ignored) {
        }
        open = false;
        closeQuietly();
        listener.onClosed("本地主动关闭（1000）");
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        final OutputStream o = out;
        if (o == null) return;
        final ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);                       // FIN + opcode
        final int len = payload.length;
        if (len < 126) {
            f.write(0x80 | len);                      // 客户端必须 mask
        } else if (len < 65536) {
            f.write(0x80 | 126);
            f.write((len >> 8) & 0xff);
            f.write(len & 0xff);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) len >> (8 * i)) & 0xff));
        }
        final byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < len; i++) f.write(payload[i] ^ mask[i & 3]);
        o.write(f.toByteArray());
        o.flush();
    }

    private void closeQuietly() {
        final Socket s = socket;
        socket = null;
        out = null;
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
