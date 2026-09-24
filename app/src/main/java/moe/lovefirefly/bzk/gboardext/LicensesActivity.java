package moe.lovefirefly.bzk.gboardext;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.InputStream;

/**
 * 「开源许可」页：把**随 APK 分发**的第三方组件与**由用户下载**的模型许可摆出来。
 *
 * <p><b>为什么必须有这一页</b>：模块重新分发了 {@code libsherpa-onnx-jni.so}（Apache-2.0）、
 * {@code libonnxruntime.so}（MIT）、{@code silero_vad.onnx}（MIT）、Rhino（MPL-2.0）等 ——
 * 这些许可都要求附许可与 NOTICE；SenseVoice / Paraformer 的权重许可（FunASR Model License
 * §2.2）还要求**署名并保留模型名**。把它们放在设置页里，用户随手就能看到。
 *
 * <p><b>内容来源</b>：仓库根的 {@code third_party/} 是**唯一的**一份（Gradle 把它直接当
 * assets 源目录打进 APK，见 app/build.gradle.kts），所以这里读的就是仓库里那份，不会漂移。
 */
public class LicensesActivity extends AppCompatActivity {

    private static final String TAG = "GboardExt";

    /** 摘要（人看的）：与 third_party/THIRD_PARTY_NOTICES.md 的表一致。 */
    private static final String[][] BUNDLED = {
            { "sherpa-onnx 1.13.8", "Apache-2.0",
              "离线语音识别运行时（libsherpa-onnx-jni.so + com/k2fsa/sherpa/onnx 下的 Java API）" },
            { "ONNX Runtime 1.28.2", "MIT",
              "推理后端（libonnxruntime.so，随 sherpa-onnx 的 AAR）" },
            { "silero-vad（sherpa-onnx 导出件）", "MIT",
              "VAD 模型 assets/models/silero_vad.onnx（句子切分）" },
            { "DexKit 2.2.0", "Apache-2.0 / LGPL-3.0",
              "运行期按结构定位 Gboard 的混淆类与回调（本项目按 Apache-2.0 使用）" },
            { "Mozilla Rhino 1.7.15", "MPL-2.0",
              "语音引擎脚本宿主（解释执行模式）" },
            { "libxposed API 101.0.0", "Apache-2.0",
              "LSPosed 模块接口（编译期依赖，运行期由框架提供）" },
            { "AndroidX RecyclerView 1.1.0 · Material Components 1.10.0", "Apache-2.0",
              "设置页界面" },
    };

    /** 由用户从上游下载的模型（**不随 APK 分发**）—— 许可约束仍在上游，署名不能少。 */
    private static final String[][] MODELS = {
            { "SenseVoice-Small（int8）", "FunASR Model Open Source License 1.1",
              "上游 FunAudioLLM/SenseVoice（sherpa-onnx 导出件）。该许可 §2.2 要求署名并保留模型名，"
                      + "本模块在设置页与日志里始终以原名呈现。" },
            { "Paraformer-Small（int8）", "FunASR 系列模型许可",
              "上游 sherpa-onnx 导出件（源自 ModelScope / FunASR），同样保留模型名。" },
            { "streaming zipformer zh-14M / bilingual-zh-en", "Apache-2.0",
              "icefall / k2-fsa 训练的流式模型（sherpa-onnx 导出件）。" },
            { "标点模型（中英 CT-Transformer，int8）", "见上游发布页",
              "k2-fsa/sherpa-onnx releases（punctuation-models），训练方 k2-fsa。" },
    };

    /** 许可全文文件（都在 assets 里，路径与 third_party/licenses/ 一致）。 */
    private static final String[][] TEXTS = {
            { "licenses/LICENSE-Apache-2.0.txt", "Apache-2.0 —— sherpa-onnx · DexKit · libxposed API · AndroidX · Material" },
            { "licenses/LICENSE-MIT-onnxruntime.txt", "MIT —— ONNX Runtime" },
            { "licenses/LICENSE-MIT-silero-vad.txt", "MIT —— silero-vad" },
            { "licenses/LICENSE-MPL-2.0.txt", "MPL-2.0 —— Mozilla Rhino" },
            { "licenses/NOTICE-rhino.txt", "Rhino 的 NOTICE" },
            { "licenses/LICENSE-FunASR-Model.txt", "FunASR Model License —— SenseVoice / Paraformer 权重" },
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_licenses);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());

        final LinearLayout content = findViewById(R.id.content);
        final int pad = (int) (8 * getResources().getDisplayMetrics().density);

        note(content, "本模块以 GPL-3.0 发布（见仓库根目录 LICENSE）。下面列出它「分发」与"
                + "「使用」的第三方组件与模型：随 APK 一起分发的要求附许可原文；"
                + "只由用户从上游下载的权重（不随 APK 分发）保留其上游许可与模型名。",
                true);
        header(content, "一、随 APK 分发的组件");
        for (String[] e : BUNDLED) row(content, e[0], e[1], e[2]);
        header(content, "二、由用户从上游下载的模型");
        for (String[] e : MODELS) row(content, e[0], e[1], e[2]);
        header(content, "三、许可全文");
        for (String[] t : TEXTS) {
            note(content, t[1], false);
            final String body = readAsset(t[0]);
            final TextView tv = new TextView(this);
            tv.setText(body == null ? "（读不到 " + t[0] + "）" : body);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(0, pad / 2, 0, pad);
            content.addView(tv);
        }
    }

    private void header(LinearLayout parent, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(0, (int) (20 * getResources().getDisplayMetrics().density), 0, 0);
        parent.addView(tv);
    }

    private void row(LinearLayout parent, String name, String license, String use) {
        note(parent, name + "　【" + license + "】\n" + use, false);
    }

    private void note(LinearLayout parent, String text, boolean first) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        tv.setTextColor(themeColor(first
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));
        tv.setTextIsSelectable(true);
        tv.setPadding(0, first ? 0 : (int) (10 * getResources().getDisplayMetrics().density),
                0, 0);
        parent.addView(tv);
    }

    /** 读 APK 里的 assets（= 仓库 third_party/，Gradle 直接挂的源目录）。 */
    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable tr) {
            Log.w(TAG, "licenses: 读不到 " + path + ": " + tr);
            return null;
        }
    }

    private int themeColor(int attrRes) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(this, tv.resourceId);
        }
        return tv.data;
    }
}
