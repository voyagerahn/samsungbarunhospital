package io.voyager.samsungbarunhospital.ui;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import io.voyager.samsungbarunhospital.model.BleOnlyNurse;
import io.voyager.samsungbarunhospital.model.ButtonConfig;
import io.voyager.samsungbarunhospital.network.ConfigSync;
import io.voyager.samsungbarunhospital.network.NsdDiscovery;
import io.voyager.samsungbarunhospital.network.TcpSender;
import io.voyager.samsungbarunhospital.network.ble.BleMeshManager;
import io.voyager.samsungbarunhospital.network.ble.BleMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AdminSettingsActivity extends AppCompatActivity {

    private static final int BUTTON_EDIT_REQUEST = 1001;

    private EditText     etTabletName, etPassword;
    private EditText[]   etMessages;
    private LinearLayout buttonListContainer;
    private LinearLayout nurseIpListContainer;
    private LinearLayout bleOnlyNurseListContainer;
    private TextView     tvCurrentIp;
    private TextView     tvBleStatus;
    private TextView     tvButtonCount;
    private SeekBar      seekAlarmVolume;
    private TextView     tvAlarmVolumeValue;

    // 사이드바 내비게이션: 각 배열의 같은 인덱스끼리 한 세트(내비 항목 / 표시줄 / 라벨 / 페이지)
    private View[]     navItems;
    private View[]     navIndicators;
    private TextView[] navLabels;
    private View[]     navPages;
    // getColor()는 Context가 필요해 static final로 못 두고 onCreate에서 한 번만 계산해둔다.
    private int NAV_COLOR_SELECTED;
    private int NAV_COLOR_UNSELECTED;
    private int NAV_BG_SELECTED;

    // 글씨 크기 현재값 (sp) — +/- 버튼으로 조절
    private int szLeftRoom, szLeftMsg, szLeftTime;
    private int szListRoom, szListMsg, szListTime;
    private TextView tvLeftRoomSizeVal, tvLeftMsgSizeVal, tvLeftTimeSizeVal;
    private TextView tvListRoomSizeVal, tvListMsgSizeVal, tvListTimeSizeVal;
    // 실시간 미리보기 — 실제 CallQueueActivity 카드와 같은 배경/색을 그대로 써서 관리자가 왔다갔다
    // 확인할 필요 없이 이 화면에서 바로 체감할 수 있게 한다.
    private TextView tvPreviewLeftRoom, tvPreviewLeftMsg, tvPreviewLeftTime;
    private TextView tvPreviewListRoom, tvPreviewListMsg, tvPreviewListTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_settings);

        NAV_COLOR_SELECTED   = getColor(R.color.text_accent_gold);
        NAV_COLOR_UNSELECTED = getColor(R.color.text_muted_light);
        NAV_BG_SELECTED      = getColor(R.color.bg_nav_selected);

        etTabletName         = findViewById(R.id.etTabletName);
        etPassword           = findViewById(R.id.etPassword);
        buttonListContainer  = findViewById(R.id.buttonListContainer);
        nurseIpListContainer = findViewById(R.id.nurseIpListContainer);
        bleOnlyNurseListContainer = findViewById(R.id.bleOnlyNurseListContainer);
        tvCurrentIp          = findViewById(R.id.tvCurrentIp);
        tvBleStatus          = findViewById(R.id.tvBleStatus);
        tvButtonCount        = findViewById(R.id.tvButtonCount);

        setupSidebarNav();

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

        tvPreviewLeftRoom = findViewById(R.id.tvPreviewLeftRoom);
        tvPreviewLeftMsg  = findViewById(R.id.tvPreviewLeftMsg);
        tvPreviewLeftTime = findViewById(R.id.tvPreviewLeftTime);
        tvPreviewListRoom = findViewById(R.id.tvPreviewListRoom);
        tvPreviewListMsg  = findViewById(R.id.tvPreviewListMsg);
        tvPreviewListTime = findViewById(R.id.tvPreviewListTime);

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
        findViewById(R.id.btnScanNearbyWifi).setOnClickListener(v -> showScanNearbyWifiDialog());

        // 간호사 설정 불러오기
        findViewById(R.id.btnLoadNurseConfig).setOnClickListener(v -> showNurseConfigPicker());

        // BLE 전용 간호사 (한 번도 같은 네트워크였던 적 없는 경우)
        populateBleOnlyNurseList();
        findViewById(R.id.btnScanNearbyPeers).setOnClickListener(v -> showScanNearbyPeersDialog());

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

    /** 왼쪽 사이드바 카테고리 클릭 시 해당 페이지만 보이도록 전환한다. */
    private android.graphics.Typeface navBaseTypeface;

    private void setupSidebarNav() {
        navBaseTypeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pretendard);
        navItems = new View[]{
                findViewById(R.id.navDeviceInfo), findViewById(R.id.navComm),
                findViewById(R.id.navDisplay), findViewById(R.id.navMessages),
                findViewById(R.id.navButtons)
        };
        navIndicators = new View[]{
                findViewById(R.id.navDeviceInfoIndicator), findViewById(R.id.navCommIndicator),
                findViewById(R.id.navDisplayIndicator), findViewById(R.id.navMessagesIndicator),
                findViewById(R.id.navButtonsIndicator)
        };
        navLabels = new TextView[]{
                findViewById(R.id.navDeviceInfoLabel), findViewById(R.id.navCommLabel),
                findViewById(R.id.navDisplayLabel), findViewById(R.id.navMessagesLabel),
                findViewById(R.id.navButtonsLabel)
        };
        navPages = new View[]{
                findViewById(R.id.pageDeviceInfo), findViewById(R.id.pageComm),
                findViewById(R.id.pageDisplay), findViewById(R.id.pageMessages),
                findViewById(R.id.pageButtons)
        };

        for (int i = 0; i < navItems.length; i++) {
            final int index = i;
            navItems[i].setOnClickListener(v -> selectNavPage(index));
        }
        selectNavPage(0);
    }

    private void selectNavPage(int selected) {
        for (int i = 0; i < navItems.length; i++) {
            boolean isSelected = i == selected;
            navIndicators[i].setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            navItems[i].setBackgroundColor(isSelected ? NAV_BG_SELECTED : 0x00000000);
            navLabels[i].setTextColor(isSelected ? NAV_COLOR_SELECTED : NAV_COLOR_UNSELECTED);
            // null을 넘기면 기본 시스템 글꼴로 돌아가 Pretendard가 풀리므로, 앱 글꼴을 기준으로 굵기만 바꾼다.
            // (현재 typeface를 기준으로 하면 한 번 굵게 된 뒤 NORMAL로 되돌릴 수 없다)
            navLabels[i].setTypeface(navBaseTypeface, isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            navPages[i].setVisibility(isSelected ? View.VISIBLE : View.GONE);
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
                    if (!ip.isEmpty()) addNurseIp(ip);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** IP 직접 입력과 Wi-Fi 자동 검색이 공유하는 등록 흐름. */
    private void addNurseIp(String ip) {
        List<String> ips = AppSettings.getNurseIps(this);
        if (ips.contains(ip)) {
            Toast.makeText(this, "이미 등록된 IP입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ips.add(ip);
        AppSettings.saveNurseIps(this, ips);
        populateNurseIpList();

        // 등록 직후 한 번 접속해서 BLE peerId를 학습한다. 예전에는 실패해도 조용히 넘어갔는데,
        // 다른 Wi-Fi에 있는 간호사는 여기서 반드시 실패하고 → peerId를 영영 못 배우고 → BLE로도
        // 호출이 안 가는 원인이 됐다. 실패하면 그 자리에서 블루투스 연결을 권한다.
        checkNurseConnection(ip, ok -> {
            if (!ok) offerBluetoothLink(ip);
        });
    }

    private void offerBluetoothLink(String ip) {
        if (!BleMeshManager.isSupported(getApplicationContext())) {
            Toast.makeText(this, ip + "에 지금 연결할 수 없습니다. 같은 Wi-Fi인지, 간호사 앱이 켜져 있는지 확인하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("연결할 수 없는 IP")
                .setMessage(ip + "에 지금 연결할 수 없습니다.\n\n"
                        + "간호사 태블릿이 다른 Wi-Fi에 있다면 IP만으로는 호출이 전달되지 않습니다. "
                        + "간호사 태블릿 근처에서 블루투스로 찾아 이 IP에 연결해두면, Wi-Fi가 안 될 때 블루투스로 호출합니다.")
                .setPositiveButton("블루투스로 연결", (d, w) -> showLinkBluetoothDialog(ip))
                .setNegativeButton("나중에", null)
                .show();
    }

    /**
     * CONFIG_REQUEST로 연결을 확인한다 — 별도 PING 메시지를 새로 만들지 않은 이유: 구버전 간호사
     * 앱은 모르는 메시지를 전부 "환자 호출"로 해석해서 가짜 알람을 띄우기 때문. CONFIG_REQUEST는
     * 이미 모든 버전이 이해하고, 응답에 BLE peerId까지 들어 있어 학습도 같이 된다.
     *
     * @param onResult 메인 스레드에서 호출됨, null 가능
     */
    private void checkNurseConnection(String ip, TcpSender.Callback onResult) {
        TcpSender.requestConfig(ip, rawResponse -> {
            ConfigSync.RemoteConfig remote = ConfigSync.parseResponsePayload(rawResponse);
            boolean ok = remote != null;
            if (ok) {
                AppSettings.saveNurseLastContact(this, ip, System.currentTimeMillis());
                AppSettings.saveNursePeerId(this, ip, remote.blePeerId);
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                populateNurseIpList();
                if (onResult != null) onResult.onResult(ok);
            });
        });
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
            AppSettings.saveNurseLastContact(this, nurseIp, System.currentTimeMillis());
            populateNurseIpList();
            confirmApplyRemoteConfig(remote);
        }));
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

        for (String ip : ips) {
            nurseIpListContainer.addView(buildNurseIpRow(ip));
        }

        if (ips.isEmpty()) {
            nurseIpListContainer.addView(buildEmptyLabel("등록된 간호사 태블릿이 없습니다."));
        }
    }

    /**
     * IP + 진단 한 줄(마지막 연결 확인 시각 · 블루투스 연결 여부) + 테스트/블루투스 연결/삭제.
     * "블루투스: 연결 안 됨"이면 Wi-Fi가 안 될 때 이 간호사에게는 BLE로도 호출이 안 간다는 뜻이라,
     * 관리자가 현장에서 바로 알아보고 조치할 수 있게 행마다 보여준다.
     */
    private LinearLayout buildNurseIpRow(String ip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 10, 0, 10);

        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvIp = new TextView(this);
        tvIp.setText(ip);
        tvIp.setTextColor(getColor(R.color.text_primary));
        tvIp.setTextSize(16f);
        textArea.addView(tvIp);

        long lastContact = AppSettings.getNurseLastContactMs(this, ip);
        boolean bleLinked = AppSettings.getNursePeerId(this, ip) != null;
        boolean bleSupported = BleMeshManager.isSupported(getApplicationContext());
        StringBuilder status = new StringBuilder("마지막 연결 확인: ")
                .append(lastContact == 0L ? "기록 없음" : formatAgo(lastContact));
        if (bleSupported) status.append("  ·  블루투스: ").append(bleLinked ? "연결됨" : "연결 안 됨");

        TextView tvStatus = new TextView(this);
        tvStatus.setText(status);
        tvStatus.setTextColor(getColor(bleSupported && !bleLinked ? R.color.badge_warning : R.color.text_muted));
        tvStatus.setTextSize(13f);
        textArea.addView(tvStatus);
        row.addView(textArea);

        row.addView(buildRowButton("테스트", R.drawable.bg_button_neutral, v -> {
            Toast.makeText(this, ip + " 연결 확인 중...", Toast.LENGTH_SHORT).show();
            checkNurseConnection(ip, ok -> {
                if (ok) {
                    Toast.makeText(this, "✓ " + ip + " 연결 정상", Toast.LENGTH_SHORT).show();
                } else {
                    offerBluetoothLink(ip);
                }
            });
        }));
        if (bleSupported) {
            row.addView(buildRowButton(bleLinked ? "블루투스 재연결" : "블루투스 연결",
                    R.drawable.bg_button_neutral, v -> showLinkBluetoothDialog(ip)));
        }
        row.addView(buildRowButton("삭제", R.drawable.bg_button_danger, v -> {
            List<String> current = AppSettings.getNurseIps(this);
            current.remove(ip);
            AppSettings.saveNurseIps(this, current);
            populateNurseIpList();
        }));
        return row;
    }

    private Button buildRowButton(String text, int backgroundRes, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13f);
        btn.setTextColor(getColor(R.color.text_primary));
        btn.setBackgroundResource(backgroundRes);
        btn.setPadding(24, 0, 24, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(12);
        btn.setLayoutParams(p);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private static String formatAgo(long epochMs) {
        long minutes = (System.currentTimeMillis() - epochMs) / 60000L;
        if (minutes <= 0) return "방금 전";
        if (minutes < 60) return minutes + "분 전";
        if (minutes < 60 * 24) return (minutes / 60) + "시간 전";
        return (minutes / (60 * 24)) + "일 전";
    }

    private void populateBleOnlyNurseList() {
        bleOnlyNurseListContainer.removeAllViews();
        List<BleOnlyNurse> nurses = AppSettings.getBleOnlyNurses(this);

        for (BleOnlyNurse nurse : nurses) {
            String label = nurse.label != null ? nurse.label : ("기기 " + nurse.peerId.substring(0, 8));
            bleOnlyNurseListContainer.addView(buildDeletableRow(label, () -> {
                AppSettings.removeBleOnlyNurse(this, nurse.peerId);
                populateBleOnlyNurseList();
            }));
        }

        if (nurses.isEmpty()) {
            bleOnlyNurseListContainer.addView(buildEmptyLabel("BLE로 등록된 간호사가 없습니다."));
        }
    }

    /** 라벨 텍스트 + "삭제" 버튼 한 행 (BLE 전용 간호사 목록). */
    private LinearLayout buildDeletableRow(String label, Runnable onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(getColor(R.color.text_primary));
        tvLabel.setTextSize(16f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLabel.setLayoutParams(p);

        row.addView(tvLabel);
        row.addView(buildRowButton("삭제", R.drawable.bg_button_danger, v -> onDelete.run()));
        return row;
    }

    /** "목록이 비어있음" 안내 텍스트. 간호사 IP·BLE 전용 간호사·근처 기기 검색 목록이 동일한 스타일을 쓴다. */
    private TextView buildEmptyLabel(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextColor(getColor(R.color.text_muted));
        empty.setTextSize(14f);
        return empty;
    }

    /** 근처 기기 검색에서 한 기기를 고르고 2차 확인까지 끝났을 때 호출됨. */
    private interface NearbyPeerPicked {
        void onPicked(String peerId, String displayName);
    }

    /** "BLE 전용 간호사" 카드 — IP 없이 peerId만으로 간호사를 등록한다. */
    private void showScanNearbyPeersDialog() {
        Set<String> alreadyRegistered = new HashSet<>();
        for (BleOnlyNurse n : AppSettings.getBleOnlyNurses(this)) alreadyRegistered.add(n.peerId);

        showNearbyBlePicker("근처 BLE 기기 검색", "등록", "간호사 태블릿으로 등록할까요?", alreadyRegistered,
                (peerId, name) -> {
                    AppSettings.addBleOnlyNurse(this, peerId, name);
                    populateBleOnlyNurseList();
                    Toast.makeText(this, "\"" + name + "\"을(를) 간호사로 등록했습니다.", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 이미 IP로 등록된 간호사에 BLE peerId를 직접 연결한다 — 다른 Wi-Fi에 있어 TCP로는 peerId를
     * 학습할 수 없는 경우를 위한 경로. 이후 Wi-Fi 전송이 실패하면 이 peerId로 블루투스 호출이 간다.
     */
    private void showLinkBluetoothDialog(String ip) {
        showNearbyBlePicker(ip + " 블루투스 연결", "연결", ip + " 간호사 태블릿으로 연결할까요?", new HashSet<>(),
                (peerId, name) -> {
                    AppSettings.saveNursePeerId(this, ip, peerId);
                    populateNurseIpList();
                    Toast.makeText(this, "✓ " + ip + "에 \"" + name + "\" 블루투스 연결됨", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 근처에서 BLE로 ANNOUNCE를 보낸 기기 목록을 보여주고 하나를 고르게 한다. 다이얼로그가 떠 있는
     * 동안 1.5초마다 목록을 새로 그려서 새로 감지되는 기기가 바로 반영되게 한다. ANNOUNCE에는
     * 간호사/환자 구분이 없으므로, 고른 뒤 이름을 다시 보여주는 2차 확인을 거친다(오등록 방지).
     */
    private void showNearbyBlePicker(String title, String actionLabel, String confirmQuestion,
                                     Set<String> excludedPeerIds, NearbyPeerPicked onPicked) {
        if (!BleMeshManager.isSupported(getApplicationContext())) {
            Toast.makeText(this, "이 기기에서는 BLE를 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!BleMeshManager.isBluetoothEnabled(getApplicationContext())) {
            Toast.makeText(this, "블루투스가 꺼져 있습니다. 블루투스를 켠 뒤 다시 시도하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        BleMeshManager.triggerAnnounceNow();

        LinearLayout container = buildPickerContainer(
                "연결할 간호사 태블릿 옆에서 이 화면을 열어두세요. 잠시 후 목록에 나타납니다.",
                "⚠ 이 목록에는 간호사 태블릿뿐 아니라 근처의 다른 환자 태블릿도 함께 나타날 수 있습니다. 이름을 반드시 확인하세요.");
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(listContainer);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setNegativeButton("닫기", null)
                .create();

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            if (!dialog.isShowing()) return;
            listContainer.removeAllViews();
            boolean any = false;
            for (BleMeshManager.DiscoveredPeer peer : BleMeshManager.getDiscoveredPeers()) {
                if (excludedPeerIds.contains(peer.peerId)) continue;
                any = true;
                String display = (peer.name != null && !peer.name.isEmpty()) ? peer.name : ("기기 " + peer.peerId.substring(0, 8));
                listContainer.addView(buildPickRow(display, null, actionLabel, () ->
                        new AlertDialog.Builder(this)
                                .setTitle(actionLabel + " 확인")
                                .setMessage("\"" + display + "\"을(를) " + confirmQuestion
                                        + "\n\n다른 환자 태블릿을 잘못 고르면 호출이 전달되지 않으니, 이름이 맞는지 다시 한 번 확인해주세요.")
                                .setPositiveButton(actionLabel, (d, w) -> {
                                    dialog.dismiss();
                                    onPicked.onPicked(peer.peerId, display);
                                })
                                .setNegativeButton("취소", null)
                                .show()));
            }
            if (!any) listContainer.addView(buildEmptyLabel("근처에 감지된 기기가 없습니다. 잠시만 기다려주세요..."));
            handler.postDelayed(refreshRef[0], 1500);
        };
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();
        refreshRef[0].run();
    }

    /**
     * 같은 Wi-Fi에서 이 앱이 켜져 있는 태블릿을 mDNS(NSD)로 찾아 IP를 몰라도 등록할 수 있게 한다.
     * 찾은 기기를 추가하면 IP 직접 입력과 같은 흐름(addNurseIp)을 탄다.
     */
    private void showScanNearbyWifiDialog() {
        LinearLayout container = buildPickerContainer(
                "같은 Wi-Fi에 있고 앱이 켜져 있는 태블릿이 나타납니다.",
                "⚠ 다른 환자 태블릿도 함께 나타날 수 있습니다. 이름을 반드시 확인하세요.");
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(listContainer);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("근처 Wi-Fi 기기 검색")
                .setView(container)
                .setNegativeButton("닫기", null)
                .create();

        String myIp = getLocalIpAddress();
        Map<String, String> found = new LinkedHashMap<>(); // ip → 태블릿 이름
        Runnable render = () -> {
            listContainer.removeAllViews();
            List<String> registered = AppSettings.getNurseIps(this);
            boolean any = false;
            for (Map.Entry<String, String> e : found.entrySet()) {
                String ip = e.getKey(), name = e.getValue();
                if (registered.contains(ip)) continue;
                any = true;
                listContainer.addView(buildPickRow(name, ip, "추가", () ->
                        new AlertDialog.Builder(this)
                                .setTitle("간호사 태블릿 추가")
                                .setMessage("\"" + name + "\" (" + ip + ")을(를) 간호사 태블릿으로 추가할까요?")
                                .setPositiveButton("추가", (d, w) -> {
                                    dialog.dismiss();
                                    addNurseIp(ip);
                                })
                                .setNegativeButton("취소", null)
                                .show()));
            }
            if (!any) listContainer.addView(buildEmptyLabel("검색 중... 잠시만 기다려주세요."));
        };

        NsdDiscovery discovery = new NsdDiscovery(this, (name, ip) -> {
            if (ip.equals(myIp)) return;
            found.put(ip, name);
            if (dialog.isShowing()) render.run();
        });
        dialog.setOnDismissListener(d -> discovery.stop());
        dialog.show();
        render.run();
        discovery.start();
    }

    /** 근처 기기 검색 다이얼로그(BLE·Wi-Fi 공통)의 안내문 + 경고문 머리 부분. */
    private LinearLayout buildPickerContainer(String hintText, String warningText) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 20, 40, 20);

        TextView hint = new TextView(this);
        hint.setText(hintText);
        hint.setTextColor(getColor(R.color.text_muted));
        hint.setTextSize(13f);
        hint.setPadding(0, 0, 0, 8);
        container.addView(hint);

        TextView warning = new TextView(this);
        warning.setText(warningText);
        warning.setTextColor(getColor(R.color.badge_warning));
        warning.setTextSize(13f);
        warning.setPadding(0, 0, 0, 16);
        container.addView(warning);
        return container;
    }

    /** 검색 결과 한 행: 이름(+선택적 부제) + 동작 버튼. */
    private LinearLayout buildPickRow(String title, String subtitle, String actionLabel, Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 12);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getColor(R.color.text_primary));
        tvTitle.setTextSize(16f);
        textArea.addView(tvTitle);

        if (subtitle != null) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextColor(getColor(R.color.text_muted));
            tvSub.setTextSize(13f);
            textArea.addView(tvSub);
        }

        row.addView(textArea);
        row.addView(buildRowButton(actionLabel, R.drawable.bg_button_success, v -> onAction.run()));
        return row;
    }

    private void populateButtonList() {
        buttonListContainer.removeAllViews();
        List<ButtonConfig> configs = AppSettings.getButtonConfigs(this);
        tvButtonCount.setText(configs.size() + "/" + AppSettings.MAX_BUTTONS + "개 사용 중");

        for (int i = 0; i < configs.size(); i++) {
            ButtonConfig config  = configs.get(i);
            final int    position = i;

            View row = LayoutInflater.from(this).inflate(R.layout.item_button_config, buttonListContainer, false);

            ImageView ivThumb  = row.findViewById(R.id.ivThumb);
            TextView  tvType   = row.findViewById(R.id.tvType);
            TextView  tvTarget = row.findViewById(R.id.tvTarget);
            Button    btnEdit  = row.findViewById(R.id.btnEdit);
            Button    btnDel   = row.findViewById(R.id.btnDelete);

            if (!ButtonImageLoader.tryLoadCustomImage(ivThumb, config, null)) {
                ButtonImageLoader.setDrawableOrPlaceholder(ivThumb, this, config.drawableRes);
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

        tvPreviewLeftRoom.setTextSize(szLeftRoom);
        tvPreviewLeftMsg .setTextSize(szLeftMsg);
        tvPreviewLeftTime.setTextSize(szLeftTime);
        tvPreviewListRoom.setTextSize(szListRoom);
        tvPreviewListMsg .setTextSize(szListMsg);
        tvPreviewListTime.setTextSize(szListTime);
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

            boolean btOn = BleMeshManager.isBluetoothEnabled(getApplicationContext());
            sb.append("블루투스: ").append(btOn ? "켜짐" : "꺼짐").append("\n");
            sb.append("서비스 실행 중: ").append(BleMeshManager.isRunning() ? "예" : "아니오").append("\n");
            sb.append("현재 연결된 기기: ").append(connections).append("대\n");

            sb.append("최근 통신: ").append(lastActivity == 0L ? "아직 없음" : formatAgo(lastActivity));

            if (!btOn) {
                sb.append("\n\n⚠ 블루투스가 꺼져 있어 근거리 통신으로 호출을 주고받을 수 없습니다. 기기 설정에서 블루투스를 켜주세요.");
            } else if (connections == 0) {
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
