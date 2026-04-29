package io.falab.samsungbarunhospital.ui;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import io.falab.samsungbarunhospital.R;
import io.falab.samsungbarunhospital.data.AppSettings;
import io.falab.samsungbarunhospital.model.ButtonConfig;
import io.falab.samsungbarunhospital.network.AlertReceiverService;
import io.falab.samsungbarunhospital.network.TcpSender;

import android.os.Handler;
import android.os.Looper;

import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private ViewFlipper  mViewFlipper;
    private LinearLayout buttonContainer;
    private ImageView    bellButton;

    private LinearLayout callStatusBanner;
    private LinearLayout activeCallsContainer;
    // 호출 추적: 인덱스 i → (UUID, 메시지). UUID로 간호사쪽과 1:1 매칭
    private final List<String>       activeCallIds       = new ArrayList<>();
    private final List<String>       activeCallMsgs      = new ArrayList<>();
    private final List<String>       confirmedMessages   = new ArrayList<>(); // 완료 표시 중인 메시지
    private final Handler            bannerHandler       = new Handler(Looper.getMainLooper());
    private final List<Bitmap>       loadedBitmaps       = new ArrayList<>(); // 비트맵 추적 → onDestroy에서 recycle

    private long last_clicked_left   = 0L;
    private long last_clicked_right  = 0L;
    private long last_clicked_center = 0L;
    private int  counterLeft   = 0;
    private int  counterRight  = 0;
    private int  counterCenter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonContainer  = findViewById(R.id.buttonContainer);
        bellButton       = findViewById(R.id.bellButton);
        callStatusBanner     = findViewById(R.id.callStatusBanner);
        activeCallsContainer = findViewById(R.id.activeCallsContainer);

        // 좌측 투명 버튼: 4번 연속 클릭 → 관리자 메뉴
        findViewById(R.id.btnLeft).setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_left) < 500) { counterLeft++; } else { counterLeft = 1; }
                if (counterLeft > 3) { counterLeft = 0; showPasswordDialog("관리자 메뉴", () -> showAdminMenu()); }
                else { last_clicked_left = cur; }
            }
        });

        // 우측 투명 버튼: 런처 실행
        findViewById(R.id.btnRight).setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_right) < 500) { counterRight++; } else { counterRight = 1; }
                if (counterRight > 3) { counterRight = 0; showPasswordDialog("런처 실행", () -> startNewActivity(MainActivity.this, "com.android.launcher3")); }
                else { last_clicked_right = cur; }
            }
        });

        // 중앙 투명 버튼: 음량 잠금 앱
        findViewById(R.id.btnCenter).setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_center) < 500) { counterCenter++; } else { counterCenter = 1; }
                if (counterCenter > 3) { counterCenter = 0; showPasswordDialog("음량 관리 실행", () -> startNewActivity(MainActivity.this, "volumelock.vlocker")); }
                else { last_clicked_center = cur; }
            }
        });

        // 벨 버튼
        bellButton.setOnClickListener(v -> showCallMessageDialog());

        // UDP 수신 서비스 시작
        Intent serviceIntent = new Intent(this, AlertReceiverService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 배경 ViewFlipper
        mViewFlipper = findViewById(R.id.view_flipper);
        Animation inAnim = new AlphaAnimation(0f, 1f);
        inAnim.setDuration(300);
        mViewFlipper.setInAnimation(inAnim);
        Animation outAnim = new AlphaAnimation(1f, 0f);
        outAnim.setDuration(300);
        mViewFlipper.setOutAnimation(outAnim);
        mViewFlipper.startFlipping();
        mViewFlipper.setFlipInterval(60000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadButtons();
        AlertReceiverService.setConfirmListener((tabletName, message) -> {
            // 메시지 일치하는 첫 번째 항목 제거 (간호사 CONFIRMED 패킷은 UUID 없이 메시지만 포함)
            int idx = activeCallMsgs.indexOf(message);
            if (idx >= 0) {
                activeCallIds.remove(idx);
                activeCallMsgs.remove(idx);
            }
            confirmedMessages.add(message);
            refreshCallBanner();
            // 2초 후 완료 항목도 제거
            bannerHandler.postDelayed(() -> {
                confirmedMessages.remove(message);
                refreshCallBanner();
            }, 2000);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        AlertReceiverService.setConfirmListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 메시지 큐에 남은 지연 콜백 제거 — Activity 참조 누수 방지
        bannerHandler.removeCallbacksAndMessages(null);
        // 남은 비트맵 해제
        for (Bitmap bm : loadedBitmaps) {
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
        loadedBitmaps.clear();
    }

    // 설정에서 읽어 버튼 동적 생성
    private void loadButtons() {
        // 이전 비트맵 즉시 해제 (GC 의존 제거)
        for (Bitmap bm : loadedBitmaps) {
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
        loadedBitmaps.clear();
        buttonContainer.removeAllViews();
        List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);

        for (ButtonConfig config : configs) {
            ImageView iv = new ImageView(this);
            int size   = dpToPx(122);
            int margin = dpToPx(20);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            iv.setLayoutParams(params);
            iv.setAdjustViewBounds(true);
            iv.setElevation(dpToPx(20));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

            if (config.imagePath != null) {
                Bitmap bm = BitmapFactory.decodeFile(config.imagePath);
                if (bm != null) {
                    iv.setImageBitmap(bm);
                    loadedBitmaps.add(bm); // 추적 목록에 등록
                } else {
                    setButtonImage(iv, config.drawableRes);
                }
            } else {
                setButtonImage(iv, config.drawableRes);
            }

            final ButtonConfig finalConfig = config;
            iv.setOnClickListener(v -> {
                if (ButtonConfig.TYPE_APP.equals(finalConfig.type)) {
                    startNewActivity(this, finalConfig.target);
                } else {
                    openWebPage(finalConfig.target);
                }
            });

            buttonContainer.addView(iv);
        }
    }

    // 비밀번호 다이얼로그 (공통)
    private void showPasswordDialog(String title, Runnable onSuccess) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);
        builder.setTitle(title);
        builder.setMessage("관리자 비밀번호를 입력해주세요.");
        builder.setCancelable(false);
        builder.setPositiveButton("확인", (dialog, which) -> {
            if (AppSettings.getPassword(MainActivity.this).equals(input.getText().toString())) {
                onSuccess.run();
            } else {
                Toast.makeText(getApplicationContext(), "비밀번호가 잘못되었습니다.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("취소", (dialog, which) -> {
            last_clicked_left = 0;   counterLeft   = 0;
            last_clicked_right = 0;  counterRight  = 0;
            last_clicked_center = 0; counterCenter = 0;
        });
        builder.show();
    }

    // 관리자 메뉴
    private void showAdminMenu() {
        String[] options = {"앱 관리자 설정", "안드로이드 시스템 설정"};
        new AlertDialog.Builder(this)
                .setTitle("관리자 메뉴")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, AdminSettingsActivity.class));
                    } else {
                        Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                })
                .show();
    }

    // 간호사 호출 메시지 선택 팝업
    private void showCallMessageDialog() {
        List<String> messages = AppSettings.getCallMessages(this);
        if (messages.isEmpty()) {
            Toast.makeText(this, "관리자 설정에서 호출 메시지를 먼저 등록하세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        TextView title = new TextView(this);
        title.setText("어떤 도움이 필요하신가요?");
        title.setTextSize(20f);
        title.setTextColor(android.graphics.Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        layout.addView(title);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        AlertDialog[] dialogRef = new AlertDialog[1];

        for (String message : messages) {
            Button btn = new Button(this);
            btn.setText(message);
            btn.setTextSize(18f);
            btn.setTextColor(android.graphics.Color.WHITE);
            btn.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 120);
            params.setMargins(0, 0, 0, 16);
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                sendCallMessage(message);
            });
            layout.addView(btn);
        }

        Button btnCancel = new Button(this);
        btnCancel.setText("취소");
        btnCancel.setTextSize(16f);
        btnCancel.setTextColor(android.graphics.Color.WHITE);
        btnCancel.setBackgroundColor(android.graphics.Color.parseColor("#555555"));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100);
        cancelParams.setMargins(0, 8, 0, 0);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setOnClickListener(v -> dialogRef[0].dismiss());
        layout.addView(btnCancel);

        dialogRef[0] = new AlertDialog.Builder(this).setView(scrollView).create();
        dialogRef[0].show();
    }

    // TCP 전송: 여러 간호사 태블릿에 동시 전송, ACK 확인
    private void sendCallMessage(String message) {
        List<String> targets = AppSettings.getNurseIps(this);
        if (targets.isEmpty()) {
            Toast.makeText(this, "등록된 간호사 태블릿이 없습니다.\n관리자 설정에서 IP를 추가하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        String tabletName = AppSettings.getTabletName(this);
        // 환자 측에서 UUID 생성 → payload에 포함 → 취소 시 1:1 매칭 가능
        String callId  = java.util.UUID.randomUUID().toString();
        String payload = tabletName + "|" + message + "|" + callId;
        Toast.makeText(this, "호출 전송 중...", Toast.LENGTH_SHORT).show();

        AtomicInteger done    = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);
        int total = targets.size();

        for (String ip : targets) {
            TcpSender.send(ip, payload, ok -> {
                if (ok) success.incrementAndGet();
                if (done.incrementAndGet() == total) {
                    runOnUiThread(() -> {
                        if (success.get() > 0) {
                            Toast.makeText(this, "✓ 간호사에게 전달되었습니다.", Toast.LENGTH_SHORT).show();
                            activeCallIds.add(callId);
                            activeCallMsgs.add(message);
                            refreshCallBanner();
                        } else {
                            Toast.makeText(this, "전달 실패: 간호사 단말기에 연결할 수 없습니다.", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        }
    }

    private void refreshCallBanner() {
        activeCallsContainer.removeAllViews();
        if (activeCallIds.isEmpty() && confirmedMessages.isEmpty()) {
            callStatusBanner.setVisibility(View.GONE);
            return;
        }
        callStatusBanner.setVisibility(View.VISIBLE);

        // 완료된 항목 먼저 표시
        for (String msg : new ArrayList<>(confirmedMessages)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, 8);
            row.setLayoutParams(rowParams);

            TextView tvMsg = new TextView(this);
            tvMsg.setText("✓ 호출 완료: " + msg);
            tvMsg.setTextColor(android.graphics.Color.parseColor("#A5D6A7"));
            tvMsg.setTextSize(16f);
            row.addView(tvMsg);
            activeCallsContainer.addView(row);
        }

        // 대기 중인 항목 (스냅샷으로 복사 후 순회 — 도중 remove 방지)
        List<String> idSnapshot  = new ArrayList<>(activeCallIds);
        List<String> msgSnapshot = new ArrayList<>(activeCallMsgs);
        for (int i = 0; i < idSnapshot.size(); i++) {
            final String callId = idSnapshot.get(i);
            final String msg    = msgSnapshot.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, 8);
            row.setLayoutParams(rowParams);

            TextView tvMsg = new TextView(this);
            tvMsg.setText(msg);
            tvMsg.setTextColor(android.graphics.Color.parseColor("#90CAF9"));
            tvMsg.setTextSize(16f);
            LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvMsg.setLayoutParams(tvParams);

            Button btnCancel = new Button(this);
            btnCancel.setText("취소");
            btnCancel.setTextColor(android.graphics.Color.WHITE);
            btnCancel.setBackgroundColor(android.graphics.Color.parseColor("#C62828"));
            btnCancel.setTextSize(14f);
            btnCancel.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
            btnCancel.setOnClickListener(v -> cancelCall(callId, msg));

            row.addView(tvMsg);
            row.addView(btnCancel);
            activeCallsContainer.addView(row);
        }
    }

    private void cancelCall(String callId, String message) {
        String cancelPayload = "CANCEL|" + callId;
        for (String ip : AppSettings.getNurseIps(this)) {
            TcpSender.send(ip, cancelPayload, null);
        }
        int idx = activeCallIds.indexOf(callId);
        if (idx >= 0) {
            activeCallIds.remove(idx);
            activeCallMsgs.remove(idx);
        }
        refreshCallBanner();
        Toast.makeText(this, "호출이 취소되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            return String.format("%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) {
            return "";
        }
    }

    public void openWebPage(String url) {
        Uri webpage = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    public void startNewActivity(Context context, String packageName) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setData(Uri.parse("market://details?id=" + packageName));
            context.startActivity(intent);
        }
    }

    private void setButtonImage(ImageView iv, String drawableRes) {
        if (drawableRes != null && !drawableRes.isEmpty()) {
            int resId = getResources().getIdentifier(drawableRes, "drawable", getPackageName());
            if (resId != 0) { iv.setImageResource(resId); return; }
        }
        iv.setBackgroundResource(R.drawable.ic_button_placeholder);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
