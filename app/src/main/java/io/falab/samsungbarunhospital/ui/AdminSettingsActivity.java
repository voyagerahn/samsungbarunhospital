package io.falab.samsungbarunhospital.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.data.AppSettings;

import java.util.ArrayList;
import java.util.List;

public class AdminSettingsActivity extends AppCompatActivity {

    private EditText etNurseIp, etTabletName, etPassword;
    private EditText[] etMessages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(io.falab.samsungbarunhospital.R.layout.activity_admin_settings);

        etNurseIp    = findViewById(io.falab.samsungbarunhospital.R.id.etNurseIp);
        etTabletName = findViewById(io.falab.samsungbarunhospital.R.id.etTabletName);
        etPassword   = findViewById(io.falab.samsungbarunhospital.R.id.etPassword);

        etMessages = new EditText[]{
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg1),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg2),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg3),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg4),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg5),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg6),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg7),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg8),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg9),
                findViewById(io.falab.samsungbarunhospital.R.id.etMsg10)
        };

        etNurseIp.setText(AppSettings.getNurseIp(this));
        etTabletName.setText(AppSettings.getTabletName(this));

        List<String> saved = AppSettings.getCallMessages(this);
        for (int i = 0; i < etMessages.length; i++) {
            if (i < saved.size()) etMessages[i].setText(saved.get(i));
        }

        Button btnCancel = findViewById(io.falab.samsungbarunhospital.R.id.btnCancel);
        btnCancel.setOnClickListener(v -> finish());

        Button btnSave = findViewById(io.falab.samsungbarunhospital.R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            String ip   = etNurseIp.getText().toString().trim();
            String name = etTabletName.getText().toString().trim();
            if (ip.isEmpty())   { Toast.makeText(this, "간호사 IP를 입력하세요.", Toast.LENGTH_SHORT).show(); return; }
            if (name.isEmpty()) { Toast.makeText(this, "태블릿 이름을 입력하세요.", Toast.LENGTH_SHORT).show(); return; }

            List<String> messages = new ArrayList<>();
            for (EditText et : etMessages) {
                String msg = et.getText().toString().trim();
                if (!msg.isEmpty()) messages.add(msg);
            }
            if (messages.isEmpty()) { Toast.makeText(this, "메시지를 최소 1개 입력하세요.", Toast.LENGTH_SHORT).show(); return; }

            String pw = etPassword.getText().toString().trim();
            AppSettings.save(this, ip, name, messages, pw);
            Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
