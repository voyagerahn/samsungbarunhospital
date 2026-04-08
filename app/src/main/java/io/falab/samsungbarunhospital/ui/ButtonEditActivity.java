package io.falab.samsungbarunhospital.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.R;
import io.falab.samsungbarunhospital.data.AppSettings;
import io.falab.samsungbarunhospital.model.ButtonConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class ButtonEditActivity extends AppCompatActivity {

    public static final String EXTRA_POSITION = "position";

    private ImageView   ivPreview;
    private RadioGroup  rgType;
    private RadioButton rbWeb, rbApp;
    private EditText    etTarget;
    private TextView    tvTargetLabel;

    private String currentImagePath = null;
    private int    position         = -1; // -1 = 신규

    private final ActivityResultLauncher<Intent> pickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    String path = saveImageToInternalStorage(uri);
                    if (path != null) {
                        currentImagePath = path;
                        Bitmap bm = BitmapFactory.decodeFile(path);
                        if (bm != null) ivPreview.setImageBitmap(bm);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_button_edit);

        ivPreview     = findViewById(R.id.ivPreview);
        rgType        = findViewById(R.id.rgType);
        rbWeb         = findViewById(R.id.rbWeb);
        rbApp         = findViewById(R.id.rbApp);
        etTarget      = findViewById(R.id.etTarget);
        tvTargetLabel = findViewById(R.id.tvTargetLabel);

        position = getIntent().getIntExtra(EXTRA_POSITION, -1);

        // 기존 버튼 편집 시 데이터 로드
        if (position >= 0) {
            List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);
            if (position < configs.size()) {
                ButtonConfig config = configs.get(position);
                if (config.imagePath != null) {
                    Bitmap bm = BitmapFactory.decodeFile(config.imagePath);
                    if (bm != null) ivPreview.setImageBitmap(bm);
                    currentImagePath = config.imagePath;
                }
                if (ButtonConfig.TYPE_APP.equals(config.type)) {
                    rbApp.setChecked(true);
                } else {
                    rbWeb.setChecked(true);
                }
                etTarget.setText(config.target);
            }
        }

        // RadioGroup 변경 시 힌트/라벨 업데이트
        rgType.setOnCheckedChangeListener((group, checkedId) -> updateTargetHint());
        updateTargetHint();

        // 이미지 선택
        ivPreview.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            pickerLauncher.launch(intent);
        });

        // 저장
        findViewById(R.id.btnSave).setOnClickListener(v -> save());

        // 취소
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    private void updateTargetHint() {
        if (rbApp.isChecked()) {
            tvTargetLabel.setText("앱 패키지명");
            etTarget.setHint("예) com.google.android.youtube");
        } else {
            tvTargetLabel.setText("URL");
            etTarget.setHint("예) https://example.com");
        }
    }

    private void save() {
        String target = etTarget.getText().toString().trim();
        if (target.isEmpty()) {
            Toast.makeText(this, "URL 또는 패키지명을 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        String type = rbApp.isChecked() ? ButtonConfig.TYPE_APP : ButtonConfig.TYPE_WEB;
        // 편집 시 기존 drawableRes 유지, 신규는 null
        String drawableRes = null;
        if (position >= 0) {
            List<ButtonConfig> existing = AppSettings.getButtonConfigs(this);
            if (position < existing.size()) drawableRes = existing.get(position).drawableRes;
        }
        ButtonConfig config = new ButtonConfig(currentImagePath, drawableRes, type, target);

        List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);
        if (position >= 0 && position < configs.size()) {
            configs.set(position, config);
        } else {
            if (configs.size() >= AppSettings.MAX_BUTTONS) {
                Toast.makeText(this, "버튼은 최대 " + AppSettings.MAX_BUTTONS + "개까지 추가할 수 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            configs.add(config);
        }
        AppSettings.saveButtonConfigs(this, configs);
        setResult(RESULT_OK);
        finish();
    }

    private String saveImageToInternalStorage(Uri uri) {
        try {
            File dir = new File(getFilesDir(), "button_images");
            if (!dir.exists()) dir.mkdirs();
            String filename = "btn_" + System.currentTimeMillis() + ".jpg";
            File dest = new File(dir, filename);
            try (InputStream in  = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "이미지 저장 실패", Toast.LENGTH_SHORT).show();
            return null;
        }
    }
}
