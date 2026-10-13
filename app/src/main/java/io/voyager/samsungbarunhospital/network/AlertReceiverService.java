package io.voyager.samsungbarunhospital.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.data.CallQueue;
import io.voyager.samsungbarunhospital.data.DiagLog;
import io.voyager.samsungbarunhospital.ui.CallQueueActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class AlertReceiverService extends Service {

    private static final String SERVICE_CHANNEL_ID = "service_channel";
    private static final String ALERT_CHANNEL_ID   = "alert_channel";

    /** 간호사 확인 수신 시 MainActivity에 전달하는 콜백 (환자 태블릿용) */
    public interface ConfirmListener {
        void onCallConfirmed(String tabletName, String message);
    }
    private static volatile ConfirmListener confirmListener;
    public static void setConfirmListener(ConfirmListener l) { confirmListener = l; }

    /** 알림 갱신용 정적 인스턴스 — CallQueueActivity.onStop()에서 호출 */
    private static volatile AlertReceiverService instance;
    public static void refreshNotification() {
        AlertReceiverService svc = instance;
        if (svc != null) {
            svc.mainHandler.post(svc::updateNotificationBadge);
        }
    }

    private volatile ServerSocket serverSocket;
    private volatile NsdManager.RegistrationListener nsdRegistration;
    private volatile boolean running = true;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagLog.init(this);
        createNotificationChannels();
        startForeground(1, buildServiceNotification());
    }

    // 수신 루프를 시작했는지(메인 스레드에서만 접근). 예전에는 "serverSocket이 열려 있나"로 판단했는데,
    // 소켓은 다른 스레드에서 조금 뒤에 열리므로 onStartCommand가 연달아 두 번 오면(시스템이 서비스를
    // 재시작하는 것과 MainActivity가 서비스를 시작하는 것이 겹칠 때) 루프가 두 개 생겼다. 그러면 둘째가
    // "포트 사용 중"으로 실패 → 3초 뒤 공용 소켓을 닫음 → 첫째가 죽고 다시 열림 → … 수신 포트가 3초마다
    // 닫혔다 열리기를 반복해 Wi-Fi 호출이 간헐적으로 "전달 실패"가 된다.
    private boolean receiverStarted = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!receiverStarted) {
            receiverStarted = true;
            new Thread(this::receiveLoop, "tcp-receiver").start();
        }
        return START_STICKY;
    }

    /** 수신 루프. 한 번에 하나만 돈다 — 실패하면 자기 소켓만 닫고 3초 뒤 스스로 다시 시작한다. */
    private void receiveLoop() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true); // 재시작 직후에도 같은 포트를 바로 다시 열 수 있게
            socket.bind(new java.net.InetSocketAddress(TcpSender.PORT));
            serverSocket = socket;
            // 포트가 실제로 열린 뒤에만 광고해야, 검색으로 찾은 IP에 바로 접속이 된다.
            // 이름은 이 시점의 태블릿 이름 — 관리자가 이름을 바꾸면 앱/서비스 재시작 후 반영된다.
            if (nsdRegistration == null) {
                nsdRegistration = NsdDiscovery.register(getApplicationContext(),
                        AppSettings.getTabletName(getApplicationContext()), TcpSender.PORT);
            }
            while (running) {
                Socket client = socket.accept();
                new Thread(() -> handleClient(client), "tcp-client").start();
            }
        } catch (Exception e) {
            // 아래 finally에서 정리 후 재시작
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            if (serverSocket == socket) serverSocket = null;
        }
        if (running) {
            mainHandler.postDelayed(() -> {
                if (running) new Thread(this::receiveLoop, "tcp-receiver-retry").start();
            }, 3000);
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            PrintWriter    out = new PrintWriter(client.getOutputStream(), true);
            String payload = in.readLine();
            String fromIp  = client.getInetAddress().getHostAddress();
            if (payload != null && !payload.trim().isEmpty()) {
                String trimmed = payload.trim();
                if (ConfigSync.REQUEST.equals(trimmed)) {
                    out.println(ConfigSync.buildResponsePayload(getApplicationContext()));
                } else {
                    out.println("ACK"); // 수신 확인 즉시 응답
                    react(IncomingCallHandler.parseAndApply(trimmed, fromIp, null));
                }
            }
        } catch (Exception e) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** BLE mesh 수신 경로(BleMeshManager.ReceiveListener)에서 호출하는 정적 진입점. */
    public static void handleIncomingFromBle(String payload, String fromPeerId) {
        IncomingCallHandler.Result result = IncomingCallHandler.parseAndApply(payload, null, fromPeerId);
        if (instance == null) {
            DiagLog.log("BLE수신", "블루투스로 받았지만 호출 수신 서비스가 꺼져 있어 처리 못함 (" + result.kind + ")");
        } else if (result.kind == IncomingCallHandler.Kind.CONFIRMED && confirmListener == null) {
            DiagLog.log("BLE수신", "간호사 확인응답을 받았지만 환자 메인 화면이 열려 있지 않아 '호출 완료'를 표시 못함");
        }
        react(result);
    }

    private static void react(IncomingCallHandler.Result result) {
        AlertReceiverService svc = instance;
        if (svc == null) return; // 이론상 발생 안 함 — MainActivity가 BLE 시작 전 이미 TCP 서비스를 기동

        switch (result.kind) {
            case NEW_CALL:
                svc.mainHandler.post(() -> {
                    if (!CallQueueActivity.isRunning) {
                        Intent alertIntent = new Intent(svc, CallQueueActivity.class);
                        alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        svc.startActivity(alertIntent);
                    }
                    svc.updateNotificationBadge();
                });
                break;
            case CONFIRMED:
                if (confirmListener != null) {
                    svc.mainHandler.post(() -> confirmListener.onCallConfirmed(result.confirmedTabletName, result.confirmedMessage));
                }
                break;
            case CANCEL:
            case IGNORED:
            default:
                break;
        }
    }

    private void updateNotificationBadge() {
        int count = CallQueue.getInstance().getWaitingCount();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        if (count == 0) {
            nm.cancel(2);
            return;
        }

        Intent intent = new Intent(this, CallQueueActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("🔔 대기 호출 " + count + "건")
                .setContentText("탭하여 호출 목록 확인")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(false)
                .build();
        nm.notify(2, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        running = false;
        NsdDiscovery.unregister(getApplicationContext(), nsdRegistration);
        nsdRegistration = null;
        closeSocket();
    }

    private void closeSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
        serverSocket = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle("호출 대기 중")
                .setContentText("환자 호출 수신 대기 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel sc = new NotificationChannel(SERVICE_CHANNEL_ID, "호출 수신 서비스", NotificationManager.IMPORTANCE_LOW);
            NotificationChannel ac = new NotificationChannel(ALERT_CHANNEL_ID, "환자 호출 알람", NotificationManager.IMPORTANCE_HIGH);
            ac.enableVibration(true);
            ac.setBypassDnd(true);
            nm.createNotificationChannel(sc);
            nm.createNotificationChannel(ac);
        }
    }
}
