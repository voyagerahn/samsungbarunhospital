package io.falab.samsungbarunhospital.ui;

import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.R;
import io.falab.samsungbarunhospital.data.CallQueue;
import io.falab.samsungbarunhospital.model.CallItem;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallQueueActivity extends AppCompatActivity implements CallQueue.Listener {

    // AlertReceiverService에서 이미 실행 중인지 확인용 플래그
    public static volatile boolean isRunning = false;

    private LinearLayout callListContainer;
    private TextView tvCount;
    private TextView tvEmpty;
    private Ringtone ringtone;
    private android.net.Uri ringtoneUri;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.KOREA);

    private final Handler alarmHandler = new Handler(Looper.getMainLooper());
    private Runnable alarmRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 잠금화면 위에도 표시, 화면 켜기
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_call_queue);

        callListContainer = findViewById(R.id.callListContainer);
        tvCount           = findViewById(R.id.tvCount);
        tvEmpty           = findViewById(R.id.tvEmpty);

        // 벨소리 URI를 한 번만 결정
        ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (ringtoneUri == null) {
            ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        ringtone = RingtoneManager.getRingtone(this, ringtoneUri);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        playAlarm();
        startRepeatingAlarm();
        CallQueue.getInstance().addListener(this);
        renderList();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isRunning = true;
        renderList(); // 화면 복귀 시 최신 상태 반영
    }

    @Override
    protected void onStop() {
        super.onStop();
        isRunning = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        stopAlarm();
        stopRepeatingAlarm();
        CallQueue.getInstance().removeListener(this);
    }

    // CallQueue.Listener 콜백 (메인 스레드에서 호출됨)
    @Override
    public void onQueueChanged() {
        renderList();
        if (CallQueue.getInstance().getWaitingCount() == 0) {
            stopAlarm();
            stopRepeatingAlarm();
            finish();
        }
    }

    private void renderList() {
        List<CallItem> items = CallQueue.getInstance().getAll();
        callListContainer.removeAllViews();

        if (items.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            callListContainer.setVisibility(View.GONE);
            tvCount.setText("대기 0건");
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        callListContainer.setVisibility(View.VISIBLE);
        tvCount.setText("대기 " + items.size() + "건");

        for (CallItem item : items) {
            callListContainer.addView(buildCallItemView(item));
        }
    }

    private View buildCallItemView(CallItem item) {
        // 카드 컨테이너
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundColor(Color.parseColor("#1E1E3A"));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 20);

        // 텍스트 영역
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textArea.setLayoutParams(textParams);

        TextView tvTablet = new TextView(this);
        tvTablet.setText(item.tabletName);
        tvTablet.setTextColor(Color.parseColor("#90CAF9"));
        tvTablet.setTextSize(14f);
        textArea.addView(tvTablet);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(item.message);
        tvMsg.setTextColor(Color.WHITE);
        tvMsg.setTextSize(20f);
        textArea.addView(tvMsg);

        TextView tvTime = new TextView(this);
        tvTime.setText(timeFormat.format(new Date(item.timestamp)));
        tvTime.setTextColor(Color.parseColor("#888888"));
        tvTime.setTextSize(12f);
        textArea.addView(tvTime);

        card.addView(textArea);

        // 확인 버튼
        Button btnDone = new Button(this);
        btnDone.setText("확인");
        btnDone.setTextColor(Color.WHITE);
        btnDone.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnDone.setTextSize(16f);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(140, 120);
        btnParams.setMarginStart(16);
        btnDone.setLayoutParams(btnParams);
        btnDone.setOnClickListener(v -> CallQueue.getInstance().markDone(item.id));
        card.addView(btnDone);

        return card;
    }

    private void startRepeatingAlarm() {
        alarmRunnable = new Runnable() {
            @Override
            public void run() {
                if (CallQueue.getInstance().getWaitingCount() > 0) {
                    playAlarm();
                    alarmHandler.postDelayed(this, 10000);
                }
            }
        };
        alarmHandler.postDelayed(alarmRunnable, 10000);
    }

    private void stopRepeatingAlarm() {
        if (alarmRunnable != null) {
            alarmHandler.removeCallbacks(alarmRunnable);
            alarmRunnable = null;
        }
    }

    private void playAlarm() {
        try {
            if (ringtone == null) {
                ringtone = RingtoneManager.getRingtone(this, ringtoneUri);
            }
            if (ringtone != null) ringtone.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopAlarm() {
        try {
            if (ringtone != null) {
                ringtone.stop(); // isPlaying() 미신뢰 — null 체크만 하고 무조건 stop
                ringtone = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
