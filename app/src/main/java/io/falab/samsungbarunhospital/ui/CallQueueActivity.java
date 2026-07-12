package io.falab.samsungbarunhospital.ui;

import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.falab.samsungbarunhospital.R;
import io.falab.samsungbarunhospital.data.AppSettings;
import io.falab.samsungbarunhospital.data.CallQueue;
import io.falab.samsungbarunhospital.model.CallItem;
import io.falab.samsungbarunhospital.network.AlertReceiverService;
import io.falab.samsungbarunhospital.network.TcpSender;
import io.falab.samsungbarunhospital.network.ble.BleMeshManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallQueueActivity extends AppCompatActivity implements CallQueue.Listener {

    // AlertReceiverService에서 이미 실행 중인지 확인용 플래그
    public static volatile boolean isRunning = false;

    // Header
    private TextView tvCount;

    // Content split layout (hidden when empty)
    private LinearLayout contentLayout;

    // Left panel — primary (oldest / most urgent) call
    private TextView tvPrimaryRoom;
    private TextView tvPrimaryMsg;
    private TextView tvPrimaryTime;
    private Button   btnPrimaryConfirm;

    // Right panel — full list
    private View middleDivider;
    private LinearLayout rightPanel;
    private LinearLayout callListContainer;
    private Button btnToggleFullScreen;

    // Empty state
    private TextView tvEmpty;

    // Currently displayed primary call
    private CallItem currentPrimary;

    private MediaPlayer  alarmPlayer;
    private AudioManager audioManager;
    private int          savedAlarmVolume = -1;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable tickRunnable;

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

        tvCount             = findViewById(R.id.tvCount);
        contentLayout       = findViewById(R.id.contentLayout);
        tvPrimaryRoom       = findViewById(R.id.tvPrimaryRoom);
        tvPrimaryMsg        = findViewById(R.id.tvPrimaryMsg);
        tvPrimaryTime       = findViewById(R.id.tvPrimaryTime);
        btnPrimaryConfirm   = findViewById(R.id.btnPrimaryConfirm);
        middleDivider       = findViewById(R.id.middleDivider);
        rightPanel          = findViewById(R.id.rightPanel);
        callListContainer   = findViewById(R.id.callListContainer);
        btnToggleFullScreen = findViewById(R.id.btnToggleFullScreen);
        tvEmpty             = findViewById(R.id.tvEmpty);
        audioManager        = (AudioManager) getSystemService(AUDIO_SERVICE);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        btnPrimaryConfirm.setOnClickListener(v -> {
            if (currentPrimary != null) confirmCall(currentPrimary);
        });
        btnToggleFullScreen.setOnClickListener(v -> {
            AppSettings.setFullScreenPrimary(this, !AppSettings.isFullScreenPrimary(this));
            applyFullScreenMode();
        });
        applyFullScreenMode();

        playAlarm();
        startTick();
        CallQueue.getInstance().addListener(this);
        // renderList()는 onStart()에서 호출되므로 여기서 중복 호출 제거
    }

    @Override
    protected void onStart() {
        super.onStart();
        isRunning = true;
        renderList();
    }

    @Override
    protected void onStop() {
        super.onStop();
        isRunning = false;
        // 대기 호출이 남아있으면 notification을 현재 카운트로 갱신하여 탭 복귀 가능하게 유지
        AlertReceiverService.refreshNotification();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        stopAlarm();
        stopTick();
        CallQueue.getInstance().removeListener(this);
    }

    // CallQueue.Listener 콜백 (메인 스레드에서 호출됨)
    @Override
    public void onQueueChanged() {
        renderList();
        if (CallQueue.getInstance().getWaitingCount() == 0) {
            stopAlarm();
            stopTick();
            finish();
        }
    }

    private void applyFullScreenMode() {
        boolean fullScreen = AppSettings.isFullScreenPrimary(this);
        middleDivider.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        rightPanel.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        btnToggleFullScreen.setText(fullScreen ? "목록 보기" : "전체화면");
    }

    private void renderList() {
        List<CallItem> items = CallQueue.getInstance().getAll();

        if (items.isEmpty()) {
            contentLayout.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvCount.setText("대기 0건");
            currentPrimary = null;
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
        tvCount.setText("대기 " + items.size() + "건");

        // 글씨 크기 한 번만 읽어 캐시 — 아이템 수만큼 반복 읽기 방지
        int leftRoomSize = AppSettings.getLeftRoomSize(this);
        int leftMsgSize  = AppSettings.getLeftMsgSize(this);
        int leftTimeSize = AppSettings.getLeftTimeSize(this);
        int listRoomSize = AppSettings.getListRoomSize(this);
        int listMsgSize  = AppSettings.getListMsgSize(this);
        int listTimeSize = AppSettings.getListTimeSize(this);

        // 왼쪽 패널 글씨 크기 적용
        tvPrimaryRoom.setTextSize(leftRoomSize);
        tvPrimaryMsg .setTextSize(leftMsgSize);
        tvPrimaryTime.setTextSize(leftTimeSize);

        // Primary = 가장 오래된 호출 = 리스트 마지막 (items는 최신순, index 0이 최신)
        currentPrimary = items.get(items.size() - 1);
        tvPrimaryRoom.setText(currentPrimary.tabletName);
        tvPrimaryMsg.setText(currentPrimary.message);
        tvPrimaryTime.setText(formatElapsed(currentPrimary.timestamp));

        // 우측 전체 목록 재구성
        callListContainer.removeAllViews();
        for (CallItem item : items) {
            boolean isPrimary = item.id.equals(currentPrimary.id);
            callListContainer.addView(buildListItemView(item, isPrimary, listRoomSize, listMsgSize, listTimeSize));
        }
    }

    private void updatePrimaryTime() {
        if (currentPrimary != null && tvPrimaryTime != null) {
            tvPrimaryTime.setText(formatElapsed(currentPrimary.timestamp));
        }
    }

    private String formatElapsed(long timestamp) {
        long elapsed = System.currentTimeMillis() - timestamp;
        long seconds = elapsed / 1000;
        if (seconds < 60) return "방금 전";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "분 대기 중";
        long hours   = minutes / 60;
        long mins    = minutes % 60;
        return hours + "시간 " + mins + "분 대기 중";
    }

    private void confirmCall(CallItem item) {
        CallQueue.getInstance().markDone(item.id);
        String confirmPayload = "CONFIRMED|" + item.tabletName + "|" + item.message;
        if (item.senderIp != null && !item.senderIp.isEmpty()) {
            TcpSender.send(item.senderIp, confirmPayload, null);
        }
        if (item.senderPeerId != null && !item.senderPeerId.isEmpty()
                && BleMeshManager.isSupported(getApplicationContext())) {
            BleMeshManager.sendConfirmed(item.senderPeerId, confirmPayload);
        }
    }

    private View buildListItemView(CallItem item, boolean isPrimary, int roomSize, int msgSize, int timeSize) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(isPrimary
                ? Color.parseColor("#1A1500")   // 황금빛 강조 배경
                : Color.parseColor("#1E1E3A"));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 10);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 20);

        // 호실명 (고정 너비 — 긴 이름도 줄바꿈되어 메시지 침범 안 함)
        TextView tvRoom = new TextView(this);
        tvRoom.setText(item.tabletName);
        tvRoom.setTextColor(isPrimary
                ? Color.parseColor("#FFD54F")
                : Color.parseColor("#90CAF9"));
        tvRoom.setTextSize(roomSize);
        tvRoom.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRoom.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams roomParams = new LinearLayout.LayoutParams(dpToPx(120), LinearLayout.LayoutParams.WRAP_CONTENT);
        roomParams.setMarginEnd(dpToPx(14));
        tvRoom.setLayoutParams(roomParams);
        card.addView(tvRoom);

        // 구분선
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#444466"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(dpToPx(2), LinearLayout.LayoutParams.MATCH_PARENT);
        divParams.setMarginEnd(dpToPx(14));
        divider.setLayoutParams(divParams);
        card.addView(divider);

        // 메시지 + 시간 (남은 공간 모두 차지, 자동 줄바꿈)
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvMsg = new TextView(this);
        tvMsg.setText(item.message);
        tvMsg.setTextColor(Color.WHITE);
        tvMsg.setTextSize(msgSize);
        tvMsg.setTypeface(null, android.graphics.Typeface.BOLD);
        textArea.addView(tvMsg);

        TextView tvTime = new TextView(this);
        tvTime.setText(timeFormat.format(new Date(item.timestamp)));
        tvTime.setTextColor(Color.parseColor("#888888"));
        tvTime.setTextSize(timeSize);
        textArea.addView(tvTime);

        card.addView(textArea);

        // 확인 버튼
        Button btnDone = new Button(this);
        btnDone.setText("확인");
        btnDone.setTextColor(Color.WHITE);
        btnDone.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnDone.setTextSize(18f);
        btnDone.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dpToPx(90), LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMarginStart(12);
        btnDone.setLayoutParams(btnParams);
        btnDone.setOnClickListener(v -> confirmCall(item));
        card.addView(btnDone);

        return card;
    }

    private void startTick() {
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                CallQueue.getInstance().purgeExpiredAndNotify();
                updatePrimaryTime();
                if (CallQueue.getInstance().getWaitingCount() > 0) {
                    mainHandler.postDelayed(this, 10000);
                }
            }
        };
        mainHandler.postDelayed(tickRunnable, 10000);
    }

    private void stopTick() {
        if (tickRunnable != null) {
            mainHandler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
    }

    private void playAlarm() {
        if (alarmPlayer != null) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            // MODIFY_AUDIO_SETTINGS 권한으로 알람 스트림 볼륨 강제 설정
            // (음소거/진동 모드에서도 STREAM_ALARM은 별도 채널이라 소리 남)
            int maxVol    = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int targetVol = (int) (maxVol * AppSettings.getAlarmVolume(this) / 100.0f);
            savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, targetVol), 0);

            alarmPlayer = new MediaPlayer();
            alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)          // 무음/진동 모드 우회
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            alarmPlayer.setDataSource(this, uri);
            alarmPlayer.setLooping(true);

            // prepareAsync: 메인 스레드 블로킹 없이 준비 후 자동 시작
            // mp == alarmPlayer 체크: destroy 후 뒤늦게 콜백될 때 이미 release된 플레이어를 start하는 것 방지
            alarmPlayer.setOnPreparedListener(mp -> { if (mp == alarmPlayer) mp.start(); });
            alarmPlayer.setOnErrorListener((mp, what, extra) -> {
                stopAlarm();
                return true;
            });
            alarmPlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopAlarm() {
        // 먼저 null로 교체해서 onPrepared 콜백이 뒤늦게 와도 start하지 못하게 막음
        MediaPlayer player = alarmPlayer;
        alarmPlayer = null;

        if (player != null) {
            // stop/release를 각각 try-catch: stop이 예외를 던져도 release는 반드시 호출
            try { player.stop();    } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }

        // 원래 볼륨 복원
        if (savedAlarmVolume >= 0 && audioManager != null) {
            try { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0); }
            catch (Exception ignored) {}
            savedAlarmVolume = -1;
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
