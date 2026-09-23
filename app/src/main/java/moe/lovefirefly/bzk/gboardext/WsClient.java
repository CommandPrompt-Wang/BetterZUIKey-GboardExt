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

        void onError(String msg);

        void onClosed();
    }

    private final String url;
    private final Map<String, String> headers;
    private final Listener listener;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open;
    private volatile boolean closed;

    /**
     * 握手期间的**待发队列**。
     *
     * <p>为什么必须有：TLS+升级握手要 0.1~2 秒（实测讯飞 1.6s），而用户按完 Alt+D 立刻就开始说话
     * —— 早到的音频帧不能丢（丢了就是"丢字"），但也不能直接写（会把帧插进 HTTP 升级响应里）。
     * 所以先排队，握手成功后按顺序补发。
     */
    private final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
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
                    .append("Sec-WebSocket-Version: 13\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();

            final String head = readHeaders(in);
            if (head == null) {
                throw new java.io.IOException("握手无响应");
            }
            final int status = parseStatus(head);
            if (status != 101) {
                // 把服务端给的原因带出去（讯飞鉴权失败会在这里返回 401 + JSON）
                throw new java.io.IOException("握手失败 HTTP " + status + " " + tail(head));
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
                listener.onError(String.valueOf(tr.getMessage() != null ? tr.getMessage() : tr));
            }
        } finally {
            open = false;
            closeQuietly();
            if (!closed) {
                closed = true;
                listener.onClosed();
            }
        }
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
            if (b0 < 0) break;
            final int b1 = in.read();
            if (b1 < 0) break;
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
                case 0x0:   // continuation（服务端可能把一条 JSON 分多帧 —— 讯飞文档专门提醒过）
                    if (fin) {
                        listener.onText(new String(payload, "UTF-8"));
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
                        listener.onText(acc.toString("UTF-8"));
                    }
                    break;
                case 0x8:   // close
                    open = false;
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
        final byte[] b;
        try {
            b = text.getBytes("UTF-8");
        } catch (Throwable tr) {
            return;
        }
        if (!open) {
            // 握手还没完成：排队（不能直接写 —— 会把帧插进 HTTP 升级响应里，服务端解析出脏数据）
            synchronized (pending) {
                if (pending.size() < PENDING_MAX_FRAMES) pending.add(b);
            }
            return;
        }
        try {
            sendFrame(0x1, b);
        } catch (Throwable tr) {
            listener.onError("发送失败: " + tr);
        }
    }

    /** 握手成功后把排队的数据帧按顺序补发。 */
    private void flushPending() {
        int n = 0;
        while (true) {
            final byte[] b;
            synchronized (pending) {
                b = pending.poll();
            }
            if (b == null) break;
            try {
                sendFrame(0x1, b);
                n++;
            } catch (Throwable tr) {
                listener.onError("补发失败: " + tr);
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
        listener.onClosed();
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
