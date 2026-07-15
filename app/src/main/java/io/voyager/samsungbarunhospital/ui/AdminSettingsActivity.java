package io.voyager.samsungbarunhospital.ui;

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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.voyager.samsungbarunhospital.R;
import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.model.ButtonConfig;
import io.voyager.samsungbarunhospital.network.ConfigSync;
import io.voyager.samsungbarunhospital.network.TcpSender;
import io.voyager.samsungbarunhospital.network.ble.BleMeshManager;
import io.voyager.samsungbarunhospital.network.ble.BleMode;

import java.util.ArrayList;
import java.util.List;

public class AdminSettingsActivity extends AppCompatActivity {

    private static final int BUTTON_EDIT_REQUEST = 1001;

    private EditText     etTabletName, etPassword;
    private EditText[]   etMessages;
    private LinearLayout buttonListContainer;
    private LinearLayout nurseIpListContainer;
    private TextView     tvCurrentIp;
    private TextView     tvBleStatus;
    private SeekBar      seekAlarmVolume;
    private TextView     tvAlarmVolumeValue;

    // 글씨 크기 현재값 (sp) — +/- 버튼으로 조절
    private int szLeftRoom, szLeftMsg, szLeftTime;
    private int szListRoom, szListMsg, szListTime;
    private TextView tvLeftRoomSizeVal, tvLeftMsgSizeVal, tvLeftTimeSizeVal;
    private TextView tvListRoomSizeVal, tvListMsgSizeVal, tvListTimeSizeVal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_settings);

        etTabletName         = findViewById(R.id.etTabletName);
        etPassword           = findViewById(R.id.etPassword);
        buttonListContainer  = findViewById(R.id.buttonListContainer);
        nurseIpListContainer = findViewById(R.id.nurseIpListContainer);
        tvCurrentIp          = findViewById(R.id.tvCurrentIp);
        tvBleStatus          = findViewById(R.id.tvBleStatus);

        etMessages = new EditText[]{
                findViewById(R.id.etMsg1), findViewById(R.id.etMsg2),
                findViewById(R.id.etMsg3), findViewById(R.id.etMsg4),
                findViewById(R.id.etMsg5), findViewById(R.id.etMsg6),
                findViewById(R.id.etMsg7), findViewById(R.id.etMsg8),
                findViewById(R.id.etMsg9), findViewById(R.id.etMsg10)
        };

        seekAlarmVolume    = findViewById(R.id.seekAlarmVolume);
        tvAlarmVolumeValue = findViewById(R.id.tvAlarmVolumeValue);

        int savedVol = AppSettings.getAlarmVolume(this);
        seekAlarmVolume.setProgress(savedVol);
        tvAlarmVolumeValue.setText(savedVol + "%");
        seekAlarmVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvAlarmVolumeValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 글씨 크기 — 저장값 로드 후 +/- 버튼 연결
        szLeftRoom = AppSettings.getLeftRoomSize(this);
        szLeftMsg  = AppSettings.getLeftMsgSize(this);
        szLeftTime = AppSettings.getLeftTimeSize(this);
        szListRoom = AppSettings.getListRoomSize(this);
        szListMsg  = AppSettings.getListMsgSize(this);
        szListTime = AppSettings.getListTimeSize(this);

        tvLeftRoomSizeVal = findViewById(R.id.tvLeftRoomSizeVal);
        tvLeftMsgSizeVal  = findViewById(R.id.tvLeftMsgSizeVal);
        tvLeftTimeSizeVal = findViewById(R.id.tvLeftTimeSizeVal);
        tvListRoomSizeVal = findViewById(R.id.tvListRoomSizeVal);
        tvListMsgSizeVal  = findViewById(R.id.tvListMsgSizeVal);
        tvListTimeSizeVal = findViewById(R.id.tvListTimeSizeVal);

        refreshFontSizeLabels();

        findViewById(R.id.btnLeftRoomMinus).setOnClickListener(v -> { szLeftRoom = Math.max(AppSettings.LEFT_ROOM_MIN, szLeftRoom - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnLeftRoomPlus) .setOnClickListener(v -> { szLeftRoom = Math.min(AppSettings.LEFT_ROOM_MAX, szLeftRoom + 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnLeftMsgMinus) .setOnClickListener(v -> { szLeftMsg  = Math.max(AppSettings.LEFT_MSG_MIN,  szLeftMsg  - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnLeftMsgPlus)  .setOnClickListener(v -> { szLeftMsg  = Math.min(AppSettings.LEFT_MSG_MAX,  szLeftMsg  + 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnLeftTimeMinus).setOnClickListener(v -> { szLeftTime = Math.max(AppSettings.LEFT_TIME_MIN, szLeftTime - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnLeftTimePlus) .setOnClickListener(v -> { szLeftTime = Math.min(AppSettings.LEFT_TIME_MAX, szLeftTime + 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListRoomMinus).setOnClickListener(v -> { szListRoom = Math.max(AppSettings.LIST_ROOM_MIN, szListRoom - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListRoomPlus) .setOnClickListener(v -> { szListRoom = Math.min(AppSettings.LIST_ROOM_MAX, szListRoom + 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListMsgMinus) .setOnClickListener(v -> { szListMsg  = Math.max(AppSettings.LIST_MSG_MIN,  szListMsg  - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListMsgPlus)  .setOnClickListener(v -> { szListMsg  = Math.min(AppSettings.LIST_MSG_MAX,  szListMsg  + 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListTimeMinus).setOnClickListener(v -> { szListTime = Math.max(AppSettings.LIST_TIME_MIN, szListTime - 1); refreshFontSizeLabels(); });
        findViewById(R.id.btnListTimePlus) .setOnClickListener(v -> { szListTime = Math.min(AppSettings.LIST_TIME_MAX, szListTime + 1); refreshFontSizeLabels(); });

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

        // 간호사 설정 불러오기
        findViewById(R.id.btnLoadNurseConfig).setOnClickListener(v -> showNurseConfigPicker());

        refreshBleStatus();
        findViewById(R.id.btnRefreshBleStatus).setOnClickListener(v -> refreshBleStatus());


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
            AppSettings.saveAlarmVolume(this, seekAlarmVolume.getProgress());
            AppSettings.saveFontSizes(this, szLeftRoom, szLeftMsg, szLeftTime, szListRoom, szListMsg, szListTime);
            Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBleStatus();
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
                        learnNurseBlePeerId(ip);
                    } else {
                        Toast.makeText(this, "이미 등록된 IP입니다.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 등록된 간호사 태블릿 중 하나를 골라 그 설정(호출 메시지·버튼)을 불러오는 흐름의 시작점. */
    private void showNurseConfigPicker() {
        List<String> ips = AppSettings.getNurseIps(this);
        if (ips.isEmpty()) {
            Toast.makeText(this, "등록된 간호사 태블릿이 없습니다.\n먼저 IP를 추가하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] items = ips.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("어느 간호사 태블릿의 설정을 불러올까요?")
                .setItems(items, (d, which) -> requestNurseConfig(items[which]))
                .setNegativeButton("취소", null)
                .show();
    }

    private void requestNurseConfig(String nurseIp) {
        Toast.makeText(this, "설정을 요청하는 중...", Toast.LENGTH_SHORT).show();
        TcpSender.requestConfig(nurseIp, rawResponse -> runOnUiThread(() -> {
            ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload(rawResponse);
            if (remote == null) {
                Toast.makeText(this, "설정을 가져오지 못했습니다. 연결 상태를 확인하세요.", Toast.LENGTH_LONG).show();
                return;
            }
            AppSettings.saveNursePeerId(this, nurseIp, remote.blePeerId);
            confirmApplyRemoteConfig(remote);
        }));
    }

    /**
     * IP를 새로 등록할 때 백그라운드로 한 번 TCP 접속해서 그 간호사 태블릿의 BLE peerId를
     * 미리 학습해둔다(같은 화면에 이미 있는 "설정 불러오기"와 같은 프로토콜 재사용, 조용히
     * 시도만 하고 실패해도 안내 없음 — 지금 당장 필요한 게 아니라 "나중에 같은 네트워크가
     * 아닐 때 BLE로 이 태블릿을 콕 집어 부를 수 있도록" 미리 준비해두는 것뿐이라서).
     */
    private void learnNurseBlePeerId(String nurseIp) {
        TcpSender.requestConfig(nurseIp, rawResponse -> {
            ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload(rawResponse);
            if (remote != null && remote.blePeerId != null) {
                AppSettings.saveNursePeerId(this, nurseIp, remote.blePeerId);
            }
        });
    }

    private void confirmApplyRemoteConfig(ConfigSync.RemoteConfig remote) {
        String summary = "메시지 " + remote.messages.size() + "개, 버튼 " + remote.buttons.size() + "개를 불러왔습니다.\n"
                + "지금 이 기기에 적용할까요? (기존 설정을 덮어씁니다)";
        new AlertDialog.Builder(this)
                .setTitle("설정 적용")
                .setMessage(summary)
                .setPositiveButton("적용", (d, w) -> applyRemoteConfig(remote))
                .setNegativeButton("취소", null)
                .show();
    }

    private void applyRemoteConfig(ConfigSync.RemoteConfig remote) {
        // 메시지: 입력 필드만 채움 — 기존 메시지 편집과 동일하게 하단 "저장" 버튼을 눌러야 반영됨.
        for (int i = 0; i < etMessages.length; i++) {
            etMessages[i].setText(i < remote.messages.size() ? remote.messages.get(i) : "");
        }

        // 버튼: "+ 버튼 추가"와 동일하게 MAX_BUTTONS를 넘지 않도록 자르고, 즉시 저장.
        List<ButtonConfig> buttons = remote.buttons;
        boolean truncated = buttons.size() > AppSettings.MAX_BUTTONS;
        if (truncated) {
            buttons = buttons.subList(0, AppSettings.MAX_BUTTONS);
        }
        AppSettings.saveButtonConfigs(this, buttons);
        populateButtonList();

        String msg = "버튼 설정은 바로 적용되었습니다. 메시지는 하단 저장 버튼을 눌러야 반영됩니다.";
        if (truncated) {
            msg += "\n(간호사 태블릿에 버튼이 " + AppSettings.MAX_BUTTONS + "개보다 많아 앞의 "
                    + AppSettings.MAX_BUTTONS + "개만 적용됨)";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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

    private void refreshFontSizeLabels() {
        tvLeftRoomSizeVal.setText("글씨 " + szLeftRoom);
        tvLeftMsgSizeVal .setText("글씨 " + szLeftMsg);
        tvLeftTimeSizeVal.setText("글씨 " + szLeftTime);
        tvListRoomSizeVal.setText("글씨 " + szListRoom);
        tvListMsgSizeVal .setText("글씨 " + szListMsg);
        tvListTimeSizeVal.setText("글씨 " + szListTime);
    }

    /** 관리자가 현장에서 "왜 근거리 통신이 안 되지?"를 스스로 판단할 수 있게 하는 가벼운 진단 패널. */
    private void refreshBleStatus() {
        BleMode mode = BleMeshManager.getMode(getApplicationContext());
        StringBuilder sb = new StringBuilder();

        if (mode == BleMode.FULL) {
            sb.append("모드: 정상 (다른 태블릿이 이 기기를 찾아 연결할 수 있습니다)\n");
        } else if (mode == BleMode.CENTRAL_ONLY) {
            sb.append("모드: 제한적 (Android 7 추정 — 이 기기가 먼저 찾아서 연결할 수는 있지만, 다른 기기가 이 기기를 찾아올 수는 없습니다)\n");
        } else {
            sb.append("모드: 사용 불가 (권한이 없거나 이 기기가 지원하지 않음 — Wi-Fi를 통한 호출만 가능합니다)\n");
        }

        if (mode != BleMode.UNSUPPORTED) {
            int connections = BleMeshManager.getConnectionCount();
            long lastActivity = BleMeshManager.getLastActivityAtMs();

            sb.append("서비스 실행 중: ").append(BleMeshManager.isRunning() ? "예" : "아니오").append("\n");
            sb.append("현재 연결된 기기: ").append(connections).append("대\n");

            if (lastActivity == 0L) {
                sb.append("최근 통신: 아직 없음");
            } else {
                long minutesAgo = (System.currentTimeMillis() - lastActivity) / 60000L;
                sb.append("최근 통신: ").append(minutesAgo <= 0 ? "방금 전" : minutesAgo + "분 전");
            }

            if (connections == 0) {
                sb.append("\n\n⚠ 주변에 연결된 태블릿이 없습니다. 거리가 너무 멀거나 벽 등의 장애물이 있거나, 상대 기기의 블루투스가 꺼져 있을 수 있습니다.");
            }
        } else {
            sb.append("\n\n⚠ 설정 → 앱 권한에서 근거리 통신(블루투스, 위치) 권한을 허용해야 이 기능을 사용할 수 있습니다.");
        }

        tvBleStatus.setText(sb.toString());
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
