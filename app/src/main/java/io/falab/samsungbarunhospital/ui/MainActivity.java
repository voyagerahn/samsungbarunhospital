package io.falab.samsungbarunhospital.ui;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
import io.falab.samsungbarunhospital.network.AlertReceiverService;
import io.falab.samsungbarunhospital.network.TcpSender;

import android.net.wifi.WifiManager;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ViewFlipper mViewFlipper;
    private ImageView[] imageButtons;
    private Button buttonLeft;
    private Button buttonRight;
    private Button buttonCenter;
    private ImageView bellButton;

    private long last_clicked_left = 0L;
    private long last_clicked_right = 0L;
    private long last_clicked_center = 0L;
    private int counterLeft = 0;
    private int counterRight = 0;
    private int counterCenter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageButtons = new ImageView[6];
        imageButtons[0] = findViewById(R.id.imageButton1);
        imageButtons[1] = findViewById(R.id.imageButton2);
        imageButtons[2] = findViewById(R.id.imageButton3);
        imageButtons[3] = findViewById(R.id.imageButton4);
        imageButtons[4] = findViewById(R.id.imageButton5);
        imageButtons[5] = findViewById(R.id.imageButton6);

        imageButtons[0].setOnClickListener(v -> startNewActivity(MainActivity.this, "com.google.android.youtube"));
        imageButtons[1].setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                startNewActivity(MainActivity.this, "com.movienet.touch.wavveon");
            } else {
                openWebPage("https://www.wavve.com");
            }
        });
        imageButtons[2].setOnClickListener(v -> openWebPage("http://samsungbarunhospital.com?device=pc"));
        imageButtons[3].setOnClickListener(v -> openWebPage("https://www.naver.com"));
        imageButtons[4].setOnClickListener(v -> openWebPage("https://blog.naver.com/samsungbarunhosp"));
        imageButtons[5].setOnClickListener(v -> openWebPage("https://docs.google.com/forms/d/e/1FAIpQLSe-_scHDukGeaFu0hdPvTikb_oOKXrSIrVmZklvtg7IoWZFtA/viewform?pli=1"));

        // 좌측 투명 버튼: 4번 연속 클릭 → 비밀번호 → 관리자 메뉴
        buttonLeft = findViewById(R.id.btnLeft);
        buttonLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_left) < 500) {
                    counterLeft++;
                } else {
                    counterLeft = 1;
                }
                if (counterLeft > 3) {
                    showPasswordDialog("관리자 메뉴", () -> showAdminMenu());
                } else {
                    last_clicked_left = cur;
                }
            }
        });

        // 우측 투명 버튼: 런처 실행
        buttonRight = findViewById(R.id.btnRight);
        buttonRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_right) < 500) {
                    counterRight++;
                } else {
                    counterRight = 1;
                }
                if (counterRight > 3) {
                    showPasswordDialog("런처 실행", () -> startNewActivity(MainActivity.this, "com.android.launcher3"));
                } else {
                    last_clicked_right = cur;
                }
            }
        });

        // 중앙 투명 버튼: 음량 잠금 앱
        buttonCenter = findViewById(R.id.btnCenter);
        buttonCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public synchronized void onClick(View v) {
                long cur = System.currentTimeMillis();
                if ((cur - last_clicked_center) < 500) {
                    counterCenter++;
                } else {
                    counterCenter = 1;
                }
                if (counterCenter > 3) {
                    showPasswordDialog("음량 관리 실행", () -> startNewActivity(MainActivity.this, "volumelock.vlocker"));
                } else {
                    last_clicked_center = cur;
                }
            }
        });

        // 벨 버튼: 메시지 선택 팝업 표시
        bellButton = findViewById(R.id.bellButton);
        bellButton.setOnClickListener(v -> showCallMessageDialog());

        // UDP 수신 서비스 시작
        Intent serviceIntent = new Intent(this, AlertReceiverService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

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
            last_clicked_left = 0;
            counterLeft = 0;
            last_clicked_right = 0;
            counterRight = 0;
            last_clicked_center = 0;
            counterCenter = 0;
        });
        builder.show();
    }

    // 관리자 메뉴 선택 다이얼로그
    private void showAdminMenu() {
        String[] options = {"앱 관리자 설정", "안드로이드 시스템 설정"};
        new AlertDialog.Builder(this)
                .setTitle("관리자 메뉴")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, AdminSettingsActivity.class)); // same package
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

        // 팝업 레이아웃 구성
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        TextView title = new TextView(this);
        title.setText("어떤 도움이 필요하신가요?");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        layout.addView(title);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        // 실제 보여지는 다이얼로그를 배열로 참조 (람다 캡처용)
        AlertDialog[] dialogRef = new AlertDialog[1];

        for (String message : messages) {
            Button btn = new Button(this);
            btn.setText(message);
            btn.setTextSize(18f);
            btn.setTextColor(Color.WHITE);
            btn.setBackgroundColor(Color.parseColor("#1565C0"));
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
        btnCancel.setTextColor(Color.WHITE);
        btnCancel.setBackgroundColor(Color.parseColor("#555555"));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100);
        cancelParams.setMargins(0, 8, 0, 0);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setOnClickListener(v -> dialogRef[0].dismiss());
        layout.addView(btnCancel);

        dialogRef[0] = new AlertDialog.Builder(this)
                .setView(scrollView)
                .create();
        dialogRef[0].show();
    }

    // TCP 전송: 전달 확인(ACK) 포함
    private void sendCallMessage(String message) {
        String nurseIp = AppSettings.getNurseIp(this);

        if (nurseIp.equals(getLocalIpAddress())) {
            Toast.makeText(this, "자기 자신을 호출할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        String payload = AppSettings.getTabletName(this) + "|" + message;
        Toast.makeText(this, "호출 전송 중...", Toast.LENGTH_SHORT).show();
        TcpSender.send(nurseIp, payload, success -> runOnUiThread(() -> {
            if (success) {
                Toast.makeText(this, "✓ 간호사에게 전달되었습니다.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "전달 실패: 간호사 단말기에 연결할 수 없습니다.", Toast.LENGTH_LONG).show();
            }
        }));
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
}
