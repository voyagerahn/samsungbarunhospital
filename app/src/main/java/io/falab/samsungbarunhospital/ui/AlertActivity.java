package io.falab.samsungbarunhospital.ui;

import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.R;

public class AlertActivity extends AppCompatActivity {

    private Ringtone ringtone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 잠금화면 위에도 표시
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_alert);

        String payload = getIntent().getStringExtra("message");
        String fromIp = getIntent().getStringExtra("from");

        TextView tvMessage = findViewById(R.id.tvAlertMessage);
        TextView tvFrom = findViewById(R.id.tvAlertFrom);
        Button btnDismiss = findViewById(R.id.btnDismiss);

        // "태블릿이름|메시지" 형식 파싱
        if (payload != null && payload.contains("|")) {
            String[] parts = payload.split("\\|", 2);
            tvFrom.setText(parts[0] + " 에서 호출");
            tvMessage.setText(parts[1]);
        } else {
            tvMessage.setText(payload != null ? payload : "환자 호출");
            tvFrom.setText("발신 IP: " + fromIp);
        }

        // 알람음 재생
        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        ringtone = RingtoneManager.getRingtone(this, alarmUri);
        if (ringtone != null) ringtone.play();

        btnDismiss.setOnClickListener(v -> {
            stopAlarm();
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // 뒤로가기 버튼으로 닫히지 않도록 막음
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAlarm();
    }

    private void stopAlarm() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }
}
