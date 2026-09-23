package moe.lovefirefly.bzk.gboardext;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「添加配置文件」页 —— UI 逐字抄 BZK 的 {@code activity_import_profile.xml}：
 * 横排 RadioGroup（从文件 / 粘贴文本，**默认从文件**）+ 多行 EditText + 底部 TonalButton。
 *
 * <p>两条路都只是拿到一段脚本文本，然后交给 {@link VoiceProfiles#importScript}
 * （唯一识别码 = 脚本里的 {@code engine.id}，同名覆盖）。
 */
public class VoiceEngineAddActivity extends AppCompatActivity {

    private EditText code;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                final String text = read(uri);
                if (text == null) {
                    Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                code.setText(text);      // 读进来也放编辑框里，方便先看一眼
                Toast.makeText(this, "已读入文件，点「确定」导入", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_add);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        code = findViewById(R.id.et_code);
        final RadioGroup rg = findViewById(R.id.rg_mode);
        // 与 BZK 一致：默认「从文件」，编辑框只在「粘贴文本」时出现
        rg.setOnCheckedChangeListener((group, id) -> code.setVisibility(
                id == R.id.rb_text ? View.VISIBLE : View.GONE));

        findViewById(R.id.btn_submit).setOnClickListener(v -> {
            if (rg.getCheckedRadioButtonId() == R.id.rb_file
                    && code.getText().toString().trim().isEmpty()) {
                picker.launch(new String[] { "*/*" });    // 还没读文件 ⇒ 先选文件
                return;
            }
            final String id = VoiceProfiles.importScript(this, code.getText().toString());
            if (id == null) {
                Toast.makeText(this, "导入失败：脚本里读不到 engine.id", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "已导入 " + id + "，请勾选以启用", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private String read(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable tr) {
            return null;
        }
    }
}
