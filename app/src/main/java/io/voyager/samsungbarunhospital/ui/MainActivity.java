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
import io.voyager.samsungbarunhospital.data.DiagLog;
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
    // 간호사 태블릿이 받았다고 확인된 호출 id (Wi-Fi는 ACK 응답, 블루투스는 ACK 패킷). 메인 스레드에서만 접근.
    private final java.util.Set<String> deliveredCallIds = new java.util.HashSet<>();
    private static final long BLE_ACK_NOTICE_DELAY_MS = 4000L;
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
        DiagLog.init(this);
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

        // 블루투스로 보낸 호출의 전달 결과 — Wi-Fi 경로가 ACK 응답으로 "전달되었습니다/전달 실패"를
        // 알려주는 것과 같은 역할. 환자가 다른 앱(유튜브 등)을 보고 있는 동안에도 받아야 하므로
        // onResume/onPause가 아니라 Activity 수명 전체에 걸어둔다.
        // 간호사 확인(CONFIRMED) 수신. 예전에는 onResume~onPause 동안에만 받아서, 환자가 유튜브 등 다른 앱을
        // 보는 동안 간호사가 확인하면 그 응답이 버려지고 돌아왔을 때도 계속 "대기 중"으로 남았다.
        AlertReceiverService.setConfirmListener((tabletName, message) -> {
            // 메시지 일치하는 첫 번째 항목 제거 (간호사 CONFIRMED 패킷은 UUID 없이 메시지만 포함)
            int idx = activeCallMsgs.indexOf(message);
            if (idx >= 0) {
                // 간호사가 확인했으니 이 호출은 더 보낼 필요가 없다(수신 확인을 못 받아 재전송 중이었더라도).
                String confirmedId = activeCallIds.get(idx);
                BleMeshManager.stopCall(confirmedId);
                deliveredCallIds.remove(confirmedId);
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

        BleMeshManager.setDeliveryListener(new BleMeshManager.DeliveryListener() {
            @Override public void onCallDelivered(String callId) {
                runOnUiThread(() -> onBleCallDelivered(callId));
            }
            @Override public void onCallUndelivered(String callId) {
                runOnUiThread(() -> onBleCallUndelivered(callId));
            }
        });

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AlertReceiverService.setConfirmListener(null);
        BleMeshManager.setDeliveryListener(null);
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

        // 글씨 크기는 관리자 설정(화면·알람 설정 → 환자 호출 창 글씨 크기)에서 조절한다. 여백·버튼 높이는
        // 전부 dp 기준 — 예전엔 px 값(높이 120px 등)이라 고해상도 태블릿일수록 실제 크기가 작아졌다.
        int titleSize = AppSettings.getCallTitleSize(this);
        int msgSize   = AppSettings.getCallMsgSize(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(28), dpToPx(20), dpToPx(28), dpToPx(20));

        TextView title = new TextView(this);
        title.setText("어떤 도움이 필요하신가요?");
        title.setTextSize(titleSize);
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(20));
        layout.addView(title);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        AlertDialog[] dialogRef = new AlertDialog[1];

        for (String message : messages) {
            Button btn = new Button(this);
            btn.setText(message);
            btn.setTextSize(msgSize);
            btn.setTextColor(android.graphics.Color.WHITE);
            btn.setBackgroundResource(R.drawable.bg_button_primary);
            btn.setAllCaps(false);
            // 글씨가 커지면 버튼도 같이 커지도록 높이를 고정하지 않고 안쪽 여백으로 잡는다(긴 메시지는 줄바꿈).
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setPadding(dpToPx(16), dpToPx(18), dpToPx(16), dpToPx(18));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dpToPx(12));
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                sendCallMessage(message);
            });
            layout.addView(btn);
        }

        Button btnCancel = new Button(this);
        btnCancel.setText("취소");
        btnCancel.setTextSize(Math.max(16, Math.round(msgSize * 0.8f)));
        btnCancel.setTextColor(android.graphics.Color.WHITE);
        btnCancel.setBackgroundResource(R.drawable.bg_button_neutral_dark);
        btnCancel.setMinHeight(0);
        btnCancel.setMinimumHeight(0);
        btnCancel.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.setMargins(0, dpToPx(8), 0, 0);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setOnClickListener(v -> dialogRef[0].dismiss());
        layout.addView(btnCancel);

        dialogRef[0] = new AlertDialog.Builder(this).setView(scrollView).create();
        dialogRef[0].show();
        // 기본 다이얼로그 폭은 태블릿에서 좁아 큰 글씨의 메시지가 여러 줄로 쪼개진다 — 화면 폭의 70%로 넓힌다.
        if (dialogRef[0].getWindow() != null) {
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f);
            dialogRef[0].getWindow().setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 권한이 확인된 이후(이미 있었거나 방금 승인됨) BLE mesh 서비스 기동 — 수신 리스너는 서비스가 직접 등록. */
    private void startBleMeshIfSupported() {
        if (!BleMeshManager.isSupported(getApplicationContext())) return;
        BleMeshService.start(getApplicationContext());
    }

    // TCP 전송: 여러 간호사 태블릿에 동시 전송, ACK 확인
    private static String sanitizeField(String s) {
        return s == null ? "" : s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void sendCallMessage(String rawMessage) {
        List<String> targets = AppSettings.getNurseIps(this);
        List<BleOnlyNurse> bleOnlyNurses = AppSettings.getBleOnlyNurses(this);
        if (targets.isEmpty() && bleOnlyNurses.isEmpty()) {
            Toast.makeText(this, "등록된 간호사 태블릿이 없습니다.\n관리자 설정에서 IP를 추가하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        // 전송 형식이 "이름|메시지|호출ID" 한 줄이라, 이름·메시지에 구분자나 줄바꿈이 들어 있으면 받는 쪽이
        // 잘못 나눈다. 보낼 때 바꿔 둔다(간호사 확인응답과의 대조도 바꾼 메시지로 한다).
        final String message = sanitizeField(rawMessage);
        String tabletName = sanitizeField(AppSettings.getTabletName(this));
        // 환자 측에서 UUID 생성 → payload에 포함 → 취소 시 1:1 매칭 가능
        String callId  = java.util.UUID.randomUUID().toString();
        String payload = tabletName + "|" + message + "|" + callId;
        Toast.makeText(this, "호출 전송 중...", Toast.LENGTH_SHORT).show();

        // BLE 전용 간호사는 TCP 경로가 아예 없으므로(한 번도 같은 네트워크였던 적 없음),
        // TCP 성공/실패와 무관하게 항상 시도한다.
        int bleOnlySent = sendBleToBleOnlyNurses(bleOnlyNurses, payload, /*isCancel=*/false);
        if (!bleOnlyNurses.isEmpty() && bleOnlySent == 0) {
            DiagLog.log("BLE송신", "호출 #" + callId.substring(0, 8) + ": BLE 전용 간호사 " + bleOnlyNurses.size()
                    + "명에게 못 보냄" + bleNotSendableReason());
        }

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
            if (!anySuccess) logWifiFallback("호출 #" + callId.substring(0, 8), targets, bleFallbackSent);
            runOnUiThread(() -> reportCallResult(callId, message, anySuccess, bleOnlySent + bleFallbackSent));
        });
    }

    /**
     * 보낸 경로에 맞춰 결과를 알린다. 블루투스는 Wi-Fi와 "통신 수단만 다르고 동작은 같게" 맞춘다:
     * - Wi-Fi: 간호사 태블릿이 ACK를 돌려주면 "전달되었습니다", 아니면 "전달 실패".
     * - 블루투스: 간호사 태블릿의 수신 확인(ACK 패킷)이 오면 똑같이 "전달되었습니다"
     *   ([onBleCallDelivered]). 확인이 올 때까지 BleMeshManager가 10초 동안 다시 보내고(연결이 끊겨
     *   있으면 그동안 다시 닿기를 기다린다), 그래도 안 되면 "전달 실패"([onBleCallUndelivered]) —
     *   오래 보관했다가 늦게 전달하지 않는다(늦게 울린 호출은 방금 누른 것으로 오해된다).
     *
     * 예전에는 Wi-Fi가 실패해도 "BLE가 지원되는 기기"이기만 하면 "전달되었습니다"라고 표시해서, 환자
     * 쪽은 갔다고 보이는데 간호사 쪽에는 알람이 안 뜨는 일이 있었다.
     */
    private void reportCallResult(String callId, String message, boolean tcpSuccess, int bleSentCount) {
        if (tcpSuccess) {
            deliveredCallIds.add(callId);
            Toast.makeText(this, "✓ 간호사에게 전달되었습니다.", Toast.LENGTH_SHORT).show();
        } else if (bleSentCount > 0) {
            if (isAnyBleNurseReachable()) {
                // 곧 수신 확인이 오면 "전달되었습니다"가 뜬다. 한동안 안 오면 계속 시도 중임을 알린다.
                bannerHandler.postDelayed(() -> {
                    if (activeCallIds.contains(callId) && !deliveredCallIds.contains(callId)) {
                        Toast.makeText(this, "아직 간호사 태블릿의 수신 확인이 없습니다.\n다시 보내는 중입니다.", Toast.LENGTH_LONG).show();
                    }
                }, BLE_ACK_NOTICE_DELAY_MS);
            } else {
                Toast.makeText(this, "간호사 태블릿과 블루투스 연결이 끊겨 있습니다.\n잠시 다시 연결해 봅니다.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "전달 실패: 간호사 태블릿에 연결할 수 없습니다." + bleUnavailableHint(), Toast.LENGTH_LONG).show();
            return;
        }
        activeCallIds.add(callId);
        activeCallMsgs.add(message);
        refreshCallBanner();
    }

    /** 블루투스로 호출할 간호사(등록된 IP에 연결된 것 + BLE 전용) 중 지금 닿을 수 있는 곳이 있는지. */
    private boolean isAnyBleNurseReachable() {
        for (BleOnlyNurse n : AppSettings.getBleOnlyNurses(this)) {
            if (BleMeshManager.isPeerReachable(n.peerId)) return true;
        }
        for (String ip : AppSettings.getNurseIps(this)) {
            String peerId = AppSettings.getNursePeerId(this, ip);
            if (peerId != null && BleMeshManager.isPeerReachable(peerId)) return true;
        }
        return false;
    }

    private void onBleCallDelivered(String callId) {
        if (!activeCallIds.contains(callId)) return;      // 이미 취소했거나 간호사가 확인함
        if (!deliveredCallIds.add(callId)) return;         // 간호사가 여러 명이면 첫 확인만 알린다
        Toast.makeText(this, "✓ 간호사에게 전달되었습니다.", Toast.LENGTH_SHORT).show();
        refreshCallBanner();
    }

    private void onBleCallUndelivered(String callId) {
        if (deliveredCallIds.contains(callId)) return;     // 다른 간호사에게는 전달됐음
        int idx = activeCallIds.indexOf(callId);
        if (idx < 0) return;
        activeCallIds.remove(idx);
        activeCallMsgs.remove(idx);
        refreshCallBanner();
        Toast.makeText(this, "전달 실패: 간호사 태블릿에 호출을 전달하지 못했습니다.\n다시 호출해주세요.", Toast.LENGTH_LONG).show();
    }

    /** BLE로도 못 보낸 이유를 현장에서 바로 조치할 수 있게 한 줄로 알려준다. */
    private String bleUnavailableHint() {
        if (!BleMeshManager.isSupported(getApplicationContext())) return "";
        if (!BleMeshManager.isBluetoothEnabled(getApplicationContext())) return "\n(블루투스가 꺼져 있습니다)";
        return "\n다른 Wi-Fi에 있는 간호사라면 관리자 설정 → 통신 설정에서 '블루투스 연결'로 등록하세요.";
    }

    /**
     * Wi-Fi가 전부 실패했는데 블루투스로도 아무에게 못 보낸 경우만 기록한다(실패 이벤트). 블루투스로
     * 보내긴 했지만 그 순간 연결 0대/상대 미감지였던 경우는 BleMeshManager가 따로 기록한다.
     */
    private void logWifiFallback(String what, List<String> nurseIps, int bleSent) {
        if (bleSent > 0) return;
        int linked = 0;
        for (String ip : nurseIps) if (AppSettings.getNursePeerId(this, ip) != null) linked++;
        String reason = linked == 0
                ? "블루투스 연결된 간호사가 없음 (관리자 설정 → 통신 설정에서 '블루투스 연결' 필요)"
                : "블루투스 전송 불가" + bleNotSendableReason();
        DiagLog.log("BLE송신", what + ": Wi-Fi 실패(IP " + nurseIps.size() + "개) 후 블루투스로도 못 보냄 — " + reason);
    }

    private String bleNotSendableReason() {
        if (!BleMeshManager.isSupported(getApplicationContext())) return " (권한 없음/미지원)";
        if (!BleMeshManager.isBluetoothEnabled(getApplicationContext())) return " (블루투스 꺼짐)";
        if (!BleMeshManager.isRunning()) return " (BLE 미실행)";
        return "";
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
            // 간호사 태블릿이 받았다고 확인된 호출은 표시해 준다(아직이면 전달을 계속 시도 중인 상태).
            tvMsg.setText(deliveredCallIds.contains(callId) ? msg + "  ✓ 전달됨" : msg);
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
                    int sent = sendBleToKnownNursePeers(targets, cancelPayload, /*isCancel=*/true);
                    logWifiFallback("취소 #" + callId.substring(0, Math.min(8, callId.length())), targets, sent);
                }
            });
        }

        int idx = activeCallIds.indexOf(callId);
        if (idx >= 0) {
            activeCallIds.remove(idx);
            activeCallMsgs.remove(idx);
        }
        deliveredCallIds.remove(callId);
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
