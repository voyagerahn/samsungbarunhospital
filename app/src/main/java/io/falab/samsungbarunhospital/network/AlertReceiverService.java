package io.falab.samsungbarunhospital.network;

import android.util.Log;
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

import io.falab.samsungbarunhospital.data.AppSettings;
import io.falab.samsungbarunhospital.data.CallQueue;
import io.falab.samsungbarunhospital.model.CallItem;
import io.falab.samsungbarunhospital.ui.CallQueueActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class AlertReceiverService extends Service {

    private static final String TAG                = "AlertReceiverService";
    private static final String SERVICE_CHANNEL_ID = "service_channel";
    private static final String ALERT_CHANNEL_ID   = "alert_channel";

    private ServerSocket     serverSocket;
    private volatile boolean running = true;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
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
            Log.d(TAG, "TCP ServerSocket open on port " + TcpSender.PORT);
            while (running) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "tcp-client").start();
            }
        } catch (Exception e) {
            Log.e(TAG, "receiveLoop error: " + e.getMessage());
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
            Log.d(TAG, "Received from=" + fromIp + " payload=" + payload);
            if (payload != null && !payload.trim().isEmpty()) {
                out.println("ACK"); // 수신 확인 즉시 응답
                handleIncoming(payload.trim(), fromIp);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleClient error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void handleIncoming(String payload, String fromIp) {
        String tabletName, message;
        if (payload.contains("|")) {
            String[] parts = payload.split("\\|", 2);
            tabletName = parts[0].trim();
            message    = parts[1].trim();
        } else {
            tabletName = fromIp;
            message    = payload;
        }

        Log.d(TAG, "handleIncoming tabletName=" + tabletName + " myName=" + AppSettings.getTabletName(this));
        if (tabletName.equals(AppSettings.getTabletName(this))) return;

        CallItem item = new CallItem(tabletName, message);
        CallQueue.getInstance().add(item);

        mainHandler.post(() -> {
            if (!CallQueueActivity.isRunning) {
                Intent alertIntent = new Intent(this, CallQueueActivity.class);
                alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(alertIntent);
            }
            updateNotificationBadge();
        });
    }

    private void updateNotificationBadge() {
        int count = CallQueue.getInstance().getWaitingCount();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

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
