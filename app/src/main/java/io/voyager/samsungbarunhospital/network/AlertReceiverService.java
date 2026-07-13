package io.voyager.samsungbarunhospital.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import io.voyager.samsungbarunhospital.data.CallQueue;
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
    private volatile boolean running = true;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannels();
        startForeground(1, buildServiceNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (serverSocket != null && !serverSocket.isClosed()) return START_STICKY;
        new Thread(this::receiveLoop, "tcp-receiver").start();
        return START_STICKY;
    }

    private void receiveLoop() {
        try {
            serverSocket = new ServerSocket(TcpSender.PORT);
            while (running) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "tcp-client").start();
            }
        } catch (Exception e) {
            if (running) {
                mainHandler.postDelayed(() -> {
                    closeSocket();
                    new Thread(this::receiveLoop, "tcp-receiver-retry").start();
                }, 3000);
            }
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
                out.println("ACK"); // 수신 확인 즉시 응답
                react(IncomingCallHandler.parseAndApply(payload.trim(), fromIp, null));
            }
        } catch (Exception e) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** BLE mesh 수신 경로(BleMeshManager.ReceiveListener)에서 호출하는 정적 진입점. */
    public static void handleIncomingFromBle(String payload, String fromPeerId) {
        react(IncomingCallHandler.parseAndApply(payload, null, fromPeerId));
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
