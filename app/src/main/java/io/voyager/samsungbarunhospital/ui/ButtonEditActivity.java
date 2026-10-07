package io.voyager.samsungbarunhospital.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.voyager.samsungbarunhospital.R;
import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.model.ButtonConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ButtonEditActivity extends AppCompatActivity {

    public static final String EXTRA_POSITION = "position";

    private ImageView   ivPreview;
    private RadioGroup  rgType;
    private RadioButton rbWeb, rbApp;
    private EditText    etTarget;
    private TextView    tvTargetLabel;
    private Button      btnPickInstalledApp;

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
        btnPickInstalledApp = findViewById(R.id.btnPickInstalledApp);

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

        // 설치된 앱에서 선택 — 패키지명을 직접 타이핑하다 생기는 오타를 막는다(실제로 웨이브
        // 버튼이 존재하지 않는 패키지명으로 등록됐던 사고가 있었음).
        btnPickInstalledApp.setOnClickListener(v -> showInstalledAppPicker());

        // 저장
        findViewById(R.id.btnSave).setOnClickListener(v -> save());

        // 취소
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
    }

    private void updateTargetHint() {
        if (rbApp.isChecked()) {
            tvTargetLabel.setText("앱 패키지명");
            etTarget.setHint("예) com.google.android.youtube");
            btnPickInstalledApp.setVisibility(android.view.View.VISIBLE);
        } else {
            tvTargetLabel.setText("URL");
            etTarget.setHint("예) https://example.com");
            btnPickInstalledApp.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * 패키지명을 직접 타이핑하는 대신, 이 태블릿에 실제로 설치된 실행 가능한 앱 목록에서 골라
     * etTarget을 채운다. 매니페스트의 &lt;queries&gt;(LAUNCHER/MAIN 전체 질의, Android 11+ 패키지
     * 가시성 대응으로 이미 추가돼 있음)가 이 조회도 그대로 커버한다.
     */
    private void showInstalledAppPicker() {
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);

        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : resolveInfos) {
            if (!getPackageName().equals(info.activityInfo.packageName)) apps.add(info);
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "설치된 앱 목록을 가져오지 못했습니다. 패키지명을 직접 입력하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(apps, Comparator.comparing(a -> a.loadLabel(pm).toString().toLowerCase()));

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(24, 8, 24, 8);

        AlertDialog[] dialogRef = new AlertDialog[1];

        for (ResolveInfo info : apps) {
            String label = info.loadLabel(pm).toString();
            String packageName = info.activityInfo.packageName;
            Drawable icon = info.loadIcon(pm);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 16, 0, 16);
            row.setClickable(true);
            row.setFocusable(true);

            ImageView ivIcon = new ImageView(this);
            int iconSize = dpToPx(40);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.setMarginEnd(dpToPx(16));
            ivIcon.setLayoutParams(iconParams);
            ivIcon.setImageDrawable(icon);
            row.addView(ivIcon);

            LinearLayout textArea = new LinearLayout(this);
            textArea.setOrientation(LinearLayout.VERTICAL);
            textArea.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvLabel = new TextView(this);
            tvLabel.setText(label);
            tvLabel.setTextColor(getColor(R.color.text_primary));
            tvLabel.setTextSize(16f);
            textArea.addView(tvLabel);

            TextView tvPkg = new TextView(this);
            tvPkg.setText(packageName);
            tvPkg.setTextColor(getColor(R.color.text_muted));
            tvPkg.setTextSize(12f);
            textArea.addView(tvPkg);

            row.addView(textArea);
            row.setOnClickListener(v -> {
                etTarget.setText(packageName);
                dialogRef[0].dismiss();
            });

            listContainer.addView(row);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(listContainer);

        dialogRef[0] = new AlertDialog.Builder(this)
                .setTitle("설치된 앱에서 선택")
                .setView(scrollView)
                .setNegativeButton("취소", null)
                .create();
        dialogRef[0].show();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
