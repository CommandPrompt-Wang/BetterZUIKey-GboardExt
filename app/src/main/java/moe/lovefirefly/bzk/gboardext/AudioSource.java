package moe.lovefirefly.bzk.gboardext;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 语音会话的麦克风采集：16k / 单声道 / PCM16，按 <b>40ms = 1280B</b> 一帧交付。
 *
 * <p><b>参数为什么是这几个</b>：Gboard 自己的识别器就是这么开麦的（实测，
 * source=VOICE_RECOGNITION / 16000 / mono / PCM16），而且 40ms/1280B 正好是
 * 讯飞流式听写文档给的推荐节奏 —— 换引擎不用重采样、不用改帧长。
 *
 * <p><b>权限</b>：本模块跑在 Gboard 进程里，用的是 Gboard 的 {@code RECORD_AUDIO}
 * （实测可开麦），不需要我们自己的权限。
 *
 * <p><b>音量</b>：顺手算 RMS 映射到 0..100，喂给 Gboard 的波形回调（{@code kls.d(int)}，
 * 实测 Gboard 自己是 0..100、约 60ms 一次）。
 */
final class AudioSource {

    private static final String TAG = "GboardExt";

    /** 一帧 = 40ms @16k/16bit/mono。 */
    static final int FRAME_BYTES = 1280;

    interface Sink {
        void onFrame(byte[] buf, int len);

        void onLevel(int level);

        void onEnd();
    }

    private final Sink sink;
    private volatile boolean running;
    private AudioRecord record;
    private Thread thread;
    private int levelTick = 0;

    AudioSource(Sink sink) {
        this.sink = sink;
    }

    /** @return 是否成功开始采集（失败时已经把原因写进日志）。 */
    boolean start() {
        final int min = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "audio: getMinBufferSize=" + min + "，放弃");
            return false;
        }
        final int bufSize = Math.max(min, FRAME_BYTES * 8);
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable tr) {
            Log.w(TAG, "audio: AudioRecord 创建失败: " + tr);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "audio: 未初始化（RECORD_AUDIO 被拒？）");
            release();
            return false;
        }
        running = true;
        thread = new Thread(this::loop, "bzk-voice-audio");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void loop() {
        final byte[] frame = new byte[FRAME_BYTES];
        try {
            record.startRecording();
            Log.i(TAG, "audio: recording, frame=" + FRAME_BYTES + "B(40ms)");
        } catch (Throwable tr) {
            Log.w(TAG, "audio: startRecording 失败: " + tr);
            running = false;
            return;
        }
        while (running) {
            final int n;
            try {
                n = record.read(frame, 0, FRAME_BYTES);
            } catch (Throwable tr) {
                Log.w(TAG, "audio: read 失败: " + tr);
                break;
            }
            if (n <= 0) continue;
            try {
                sink.onFrame(frame, n);
            } catch (Throwable tr) {
                Log.w(TAG, "audio: sink 抛异常: " + tr);
            }
            // 波形：约每 60ms 报一次（Gboard 自己的节奏），值域 0..100
            if (++levelTick % 2 == 0) {
                try {
                    sink.onLevel(rmsLevel(frame, n));
                } catch (Throwable ignored) {
                }
            }
        }
        running = false;
        release();
        try {
            sink.onEnd();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "audio: stopped");
    }

    /** RMS → 0..100（系数先取经验值，P0 里对着波形调）。 */
    private static int rmsLevel(byte[] buf, int len) {
        long sum = 0;
        final int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            final int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sum += (long) s * s;
        }
        if (samples == 0) return 0;
        final double rms = Math.sqrt((double) sum / samples);
        final int v = (int) (rms / 32768.0 * 100.0 * 3.0);
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    void stop() {
        running = false;
        final Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        release();
    }

    private void release() {
        final AudioRecord r = record;
        record = null;
        if (r == null) return;
        try {
            r.stop();
        } catch (Throwable ignored) {
        }
        try {
            r.release();
        } catch (Throwable ignored) {
        }
    }
}
