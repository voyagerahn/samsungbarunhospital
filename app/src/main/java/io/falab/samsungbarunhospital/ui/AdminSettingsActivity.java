package io.falab.samsungbarunhospital.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.R;
import io.falab.samsungbarunhospital.data.AppSettings;
import io.falab.samsungbarunhospital.model.ButtonConfig;

import java.util.ArrayList;
import java.util.List;

public class AdminSettingsActivity extends AppCompatActivity {

    private static final int BUTTON_EDIT_REQUEST = 1001;

    private EditText     etTabletName, etPassword;
    private EditText[]   etMessages;
    private LinearLayout buttonListContainer;
    private LinearLayout nurseIpListContainer;
    private TextView     tvCurrentIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_settings);

        etTabletName         = findViewById(R.id.etTabletName);
        etPassword           = findViewById(R.id.etPassword);
        buttonListContainer  = findViewById(R.id.buttonListContainer);
        nurseIpListContainer = findViewById(R.id.nurseIpListContainer);
        tvCurrentIp          = findViewById(R.id.tvCurrentIp);

        etMessages = new EditText[]{
                findViewById(R.id.etMsg1), findViewById(R.id.etMsg2),
                findViewById(R.id.etMsg3), findViewById(R.id.etMsg4),
                findViewById(R.id.etMsg5), findViewById(R.id.etMsg6),
                findViewById(R.id.etMsg7), findViewById(R.id.etMsg8),
                findViewById(R.id.etMsg9), findViewById(R.id.etMsg10)
        };

        // 현재 IP 표시
        tvCurrentIp.setText(getLocalIpAddress());

        // 기존 설정 로드
        etTabletName.setText(AppSettings.getTabletName(this));
        List<String> saved = AppSettings.getCallMessages(this);
        for (int i = 0; i < etMessages.length; i++) {
            if (i < saved.size()) etMessages[i].setText(saved.get(i));
        }

        populateNurseIpList();
        populateButtonList();

        // 간호사 IP 추가 (다이얼로그)
        findViewById(R.id.btnAddNurseIp).setOnClickListener(v -> showAddIpDialog());


        // 버튼 추가
        findViewById(R.id.btnAddButton).setOnClickListener(v -> {
            List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);
            if (configs.size() >= AppSettings.MAX_BUTTONS) {
                Toast.makeText(this, "최대 " + AppSettings.MAX_BUTTONS + "개까지 추가할 수 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, ButtonEditActivity.class);
            intent.putExtra(ButtonEditActivity.EXTRA_POSITION, -1);
            startActivityForResult(intent, BUTTON_EDIT_REQUEST);
        });

        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            String name = etTabletName.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(this, "태블릿 이름을 입력하세요.", Toast.LENGTH_SHORT).show(); return; }

            List<String> messages = new ArrayList<>();
            for (EditText et : etMessages) {
                String msg = et.getText().toString().trim();
                if (!msg.isEmpty()) messages.add(msg);
            }
            if (messages.isEmpty()) { Toast.makeText(this, "메시지를 최소 1개 입력하세요.", Toast.LENGTH_SHORT).show(); return; }

            String pw = etPassword.getText().toString().trim();
            AppSettings.save(this, name, messages, pw);
            Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BUTTON_EDIT_REQUEST && resultCode == RESULT_OK) {
            populateButtonList();
        }
    }

    private void showAddIpDialog() {
        EditText etIp = new EditText(this);
        etIp.setHint("예) 192.168.1.101");
        etIp.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
                .setTitle("간호사 태블릿 IP 추가")
                .setView(etIp)
                .setPositiveButton("추가", (d, w) -> {
                    String ip = etIp.getText().toString().trim();
                    if (ip.isEmpty()) return;
                    List<String> ips = AppSettings.getNurseIps(this);
                    if (!ips.contains(ip)) {
                        ips.add(ip);
                        AppSettings.saveNurseIps(this, ips);
                        populateNurseIpList();
                    } else {
                        Toast.makeText(this, "이미 등록된 IP입니다.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void populateNurseIpList() {
        nurseIpListContainer.removeAllViews();
        List<String> ips = AppSettings.getNurseIps(this);

        for (int i = 0; i < ips.size(); i++) {
            final String ip = ips.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            TextView tvIp = new TextView(this);
            tvIp.setText(ip);
            tvIp.setTextColor(0xFFFFFFFF);
            tvIp.setTextSize(16f);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvIp.setLayoutParams(p);

            Button btnDel = new Button(this);
            btnDel.setText("삭제");
            btnDel.setTextSize(13f);
            btnDel.setTextColor(0xFFFFFFFF);
            btnDel.setBackgroundColor(0xFFB71C1C);
            btnDel.setPadding(24, 0, 24, 0);
            btnDel.setOnClickListener(v -> {
                List<String> current = AppSettings.getNurseIps(this);
                current.remove(ip);
                AppSettings.saveNurseIps(this, current);
                populateNurseIpList();
            });

            row.addView(tvIp);
            row.addView(btnDel);
            nurseIpListContainer.addView(row);
        }

        if (ips.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("등록된 간호사 태블릿이 없습니다.");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(14f);
            nurseIpListContainer.addView(empty);
        }
    }

    private void setThumbImage(ImageView iv, String drawableRes) {
        if (drawableRes != null && !drawableRes.isEmpty()) {
            int resId = getResources().getIdentifier(drawableRes, "drawable", getPackageName());
            if (resId != 0) { iv.setImageResource(resId); return; }
        }
        iv.setBackgroundResource(R.drawable.ic_button_placeholder);
    }

    private void populateButtonList() {
        buttonListContainer.removeAllViews();
        List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);

        for (int i = 0; i < configs.size(); i++) {
            ButtonConfig config  = configs.get(i);
            final int    position = i;

            View row = LayoutInflater.from(this).inflate(R.layout.item_button_config, buttonListContainer, false);

            ImageView ivThumb  = row.findViewById(R.id.ivThumb);
            TextView  tvType   = row.findViewById(R.id.tvType);
            TextView  tvTarget = row.findViewById(R.id.tvTarget);
            Button    btnEdit  = row.findViewById(R.id.btnEdit);
            Button    btnDel   = row.findViewById(R.id.btnDelete);

            if (config.imagePath != null) {
                Bitmap bm = BitmapFactory.decodeFile(config.imagePath);
                if (bm != null) { ivThumb.setImageBitmap(bm); }
                else { setThumbImage(ivThumb, config.drawableRes); }
            } else {
                setThumbImage(ivThumb, config.drawableRes);
            }
            tvType.setText(ButtonConfig.TYPE_APP.equals(config.type) ? "앱 실행" : "웹 열기");
            tvTarget.setText(config.target);

            btnEdit.setOnClickListener(v -> {
                Intent intent = new Intent(this, ButtonEditActivity.class);
                intent.putExtra(ButtonEditActivity.EXTRA_POSITION, position);
                startActivityForResult(intent, BUTTON_EDIT_REQUEST);
            });

            btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("버튼 삭제")
                            .setMessage("이 버튼을 삭제할까요?")
                            .setPositiveButton("삭제", (d, w) -> {
                                List<ButtonConfig> current = AppSettings.getButtonConfigs(this);
                                if (position < current.size()) {
                                    current.remove(position);
                                    AppSettings.saveButtonConfigs(this, current);
                                    populateButtonList();
                                }
                            })
                            .setNegativeButton("취소", null)
                            .show()
            );

            buttonListContainer.addView(row);
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            return String.format("%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) { return "알 수 없음"; }
    }
}
