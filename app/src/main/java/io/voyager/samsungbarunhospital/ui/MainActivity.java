package io.voyager.samsungbarunhospital.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

import io.voyager.samsungbarunhospital.R;
import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.model.BleOnlyNurse;
import io.voyager.samsungbarunhospital.model.ButtonConfig;
import io.voyager.samsungbarunhospital.network.AlertReceiverService;
import io.voyager.samsungbarunhospital.network.TcpSender;
import io.voyager.samsungbarunhospital.network.ble.BleMeshManager;
import io.voyager.samsungbarunhospital.network.ble.BleMeshService;
import io.voyager.samsungbarunhospital.network.ble.BlePermissions;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

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

    private boolean blePermissionRequested = false;
    private ActivityResultLauncher<String[]> blePermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // registerForActivityResult()는 Activity가 STARTED 상태가 되기 전에 등록해야 하므로 onCreate 최상단에서 처리
        blePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grantResults -> {
                    if (BleMeshManager.isSupported(getApplicationContext())) {
                        startBleMeshIfSupported();
                    } else {
                        Toast.makeText(this, "근거리 무선 보조 통신을 사용하려면 권한이 필요합니다.", Toast.LENGTH_LONG).show();
                    }
                });
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

        // BLE mesh 보조 통신 — Wi-Fi가 다른 네트워크/서브넷일 때를 위한 근거리 대체 경로.
        // 이미 권한이 있으면 바로 시작, 없으면 1회 요청(거부해도 TCP 전용으로 정상 동작).
        if (BleMeshManager.isSupported(getApplicationContext())) {
            startBleMeshIfSupported();
        } else if (!blePermissionRequested) {
            blePermissionRequested = true;
            blePermissionLauncher.launch(BlePermissions.requiredPermissions());
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

            if (!ButtonImageLoader.tryLoadCustomImage(iv, config, loadedBitmaps::add)) {
                ButtonImageLoader.setDrawableOrPlaceholder(iv, this, config.drawableRes);
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
            btn.setBackgroundResource(R.drawable.bg_button_primary);
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
        btnCancel.setBackgroundResource(R.drawable.bg_button_neutral_dark);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100);
        cancelParams.setMargins(0, 8, 0, 0);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setOnClickListener(v -> dialogRef[0].dismiss());
        layout.addView(btnCancel);

        dialogRef[0] = new AlertDialog.Builder(this).setView(scrollView).create();
        dialogRef[0].show();
    }

    /** 권한이 확인된 이후(이미 있었거나 방금 승인됨) BLE mesh 서비스 기동 — 수신 리스너는 서비스가 직접 등록. */
    private void startBleMeshIfSupported() {
        if (!BleMeshManager.isSupported(getApplicationContext())) return;
        BleMeshService.start(getApplicationContext());
    }

    // TCP 전송: 여러 간호사 태블릿에 동시 전송, ACK 확인
    private void sendCallMessage(String message) {
        List<String> targets = AppSettings.getNurseIps(this);
        List<BleOnlyNurse> bleOnlyNurses = AppSettings.getBleOnlyNurses(this);
        if (targets.isEmpty() && bleOnlyNurses.isEmpty()) {
            Toast.makeText(this, "등록된 간호사 태블릿이 없습니다.\n관리자 설정에서 IP를 추가하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        String tabletName = AppSettings.getTabletName(this);
        // 환자 측에서 UUID 생성 → payload에 포함 → 취소 시 1:1 매칭 가능
        String callId  = java.util.UUID.randomUUID().toString();
        String payload = tabletName + "|" + message + "|" + callId;
        Toast.makeText(this, "호출 전송 중...", Toast.LENGTH_SHORT).show();

        // BLE 전용 간호사는 TCP 경로가 아예 없으므로(한 번도 같은 네트워크였던 적 없음),
        // TCP 성공/실패와 무관하게 항상 시도한다.
        int bleOnlySent = sendBleToBleOnlyNurses(bleOnlyNurses, payload, /*isCancel=*/false);

        if (targets.isEmpty()) {
            reportCallResult(callId, message, false, bleOnlySent);
            return;
        }

        TcpSender.sendToAll(targets, payload, anySuccess -> {
            // BLE는 "같은 네트워크가 아닐 때"의 보조 경로이므로, TCP가 하나라도
            // 성공했으면 같은 네트워크로 이미 닿은 것 — BLE는 안 쏨(불필요한 중복
            // 전송을 없애 CallQueue dedup 레이스 발생 자체를 줄임). TCP가 전부
            // 실패했을 때만 BLE로 폴백.
            int bleFallbackSent = anySuccess ? 0 : sendBleToKnownNursePeers(targets, payload, /*isCancel=*/false);
            runOnUiThread(() -> reportCallResult(callId, message, anySuccess, bleOnlySent + bleFallbackSent));
        });
    }

    /**
     * 실제로 보낸 경로에 맞춰 결과를 알린다. 예전에는 TCP가 실패해도 "BLE가 지원되는 기기"이기만
     * 하면 "전달되었습니다"라고 표시했는데, 다른 Wi-Fi에서 IP만 등록된 간호사는 BLE peerId를 한 번도
     * 학습하지 못해 BLE로도 실제로는 아무것도 안 보내지는 경우가 있었다 — 환자 쪽은 "갔다"고 보이는데
     * 간호사 쪽에는 알람이 안 뜨는 원인. BLE는 수신 확인(ACK)이 없으므로 "전달됨"이 아니라 "보냄"으로
     * 표시하고, 간호사 확인(CONFIRMED)이 오면 배너에서 완료로 바뀐다.
     */
    private void reportCallResult(String callId, String message, boolean tcpSuccess, int bleSentCount) {
        if (tcpSuccess) {
            Toast.makeText(this, "✓ 간호사에게 전달되었습니다.", Toast.LENGTH_SHORT).show();
        } else if (bleSentCount > 0) {
            Toast.makeText(this, "블루투스로 호출을 보냈습니다.\n간호사가 확인하면 화면에 표시됩니다.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "전달 실패: 간호사 태블릿에 연결할 수 없습니다." + bleUnavailableHint(), Toast.LENGTH_LONG).show();
            return;
        }
        activeCallIds.add(callId);
        activeCallMsgs.add(message);
        refreshCallBanner();
    }

    /** BLE로도 못 보낸 이유를 현장에서 바로 조치할 수 있게 한 줄로 알려준다. */
    private String bleUnavailableHint() {
        if (!BleMeshManager.isSupported(getApplicationContext())) return "";
        if (!BleMeshManager.isBluetoothEnabled(getApplicationContext())) return "\n(블루투스가 꺼져 있습니다)";
        return "\n다른 Wi-Fi에 있는 간호사라면 관리자 설정 → 통신 설정에서 '블루투스 연결'로 등록하세요.";
    }

    private boolean isBleSendable() {
        return BleMeshManager.isSupported(getApplicationContext()) && BleMeshManager.isRunning();
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
            tvMsg.setTextColor(getColor(R.color.text_confirmed_green));
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
            tvMsg.setTextColor(getColor(R.color.text_accent_blue));
            tvMsg.setTextSize(16f);
            LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvMsg.setLayoutParams(tvParams);

            Button btnCancel = new Button(this);
            btnCancel.setText("취소");
            btnCancel.setTextColor(android.graphics.Color.WHITE);
            btnCancel.setBackgroundResource(R.drawable.bg_button_danger);
            btnCancel.setTextSize(14f);
            btnCancel.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
            btnCancel.setOnClickListener(v -> cancelCall(callId, msg));

            row.addView(tvMsg);
            row.addView(btnCancel);
            activeCallsContainer.addView(row);
        }
    }

    /**
     * TCP가 다 실패했을 때의 BLE 폴백 — 등록된 간호사 IP 중 BLE peerId를 학습해둔 것에게만
     * 콕 집어서(유니캐스트) 보낸다. 아무한테나 broadcast하면 근처의 다른 환자 태블릿도 "나한테
     * 온 호출"로 착각해서 자기 화면에 알림을 띄우게 되므로, peerId를 모르는 간호사는 그냥
     * 건너뛴다(같은 Wi-Fi에서 IP 추가/설정 불러오기, 또는 관리자 화면의 "블루투스 연결"로 학습됨).
     *
     * @return 실제로 BLE 전송을 시도한 대상 수(0이면 BLE로는 아무것도 안 보내짐)
     */
    private int sendBleToKnownNursePeers(List<String> nurseIps, String payload, boolean isCancel) {
        List<String> peerIds = new ArrayList<>();
        for (String ip : nurseIps) {
            String peerId = AppSettings.getNursePeerId(this, ip);
            if (peerId != null) peerIds.add(peerId);
        }
        return sendBleUnicast(peerIds, payload, isCancel);
    }

    /**
     * IP를 한 번도 몰랐던(한 번도 같은 네트워크였던 적 없는) 간호사 — "근처 기기 검색"으로 직접
     * 등록된 목록. TCP 경로 자체가 없으므로 TCP 성공/실패와 무관하게 항상 BLE로 시도한다.
     */
    private int sendBleToBleOnlyNurses(List<BleOnlyNurse> nurses, String payload, boolean isCancel) {
        List<String> peerIds = new ArrayList<>();
        for (BleOnlyNurse n : nurses) peerIds.add(n.peerId);
        return sendBleUnicast(peerIds, payload, isCancel);
    }

    private int sendBleUnicast(List<String> peerIds, String payload, boolean isCancel) {
        if (peerIds.isEmpty() || !isBleSendable()) return 0;
        for (String peerId : peerIds) {
            if (isCancel) {
                BleMeshManager.sendCancelToPeer(peerId, payload);
            } else {
                BleMeshManager.sendCallToPeer(peerId, payload);
            }
        }
        return peerIds.size();
    }

    private void cancelCall(String callId, String message) {
        String cancelPayload = "CANCEL|" + callId;
        List<String> targets = AppSettings.getNurseIps(this);
        sendBleToBleOnlyNurses(AppSettings.getBleOnlyNurses(this), cancelPayload, /*isCancel=*/true);

        if (!targets.isEmpty()) {
            // TCP가 하나라도 성공했으면 같은 네트워크로 이미 닿은 것 — BLE는 불필요
            TcpSender.sendToAll(targets, cancelPayload, anySuccess -> {
                if (!anySuccess) {
                    sendBleToKnownNursePeers(targets, cancelPayload, /*isCancel=*/true);
                }
            });
        }

        int idx = activeCallIds.indexOf(callId);
        if (idx >= 0) {
            activeCallIds.remove(idx);
            activeCallMsgs.remove(idx);
        }
        refreshCallBanner();
        Toast.makeText(this, "호출이 취소되었습니다.", Toast.LENGTH_SHORT).show();
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

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
