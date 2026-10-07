package io.voyager.samsungbarunhospital.network.ble

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.voyager.samsungbarunhospital.data.AppSettings
import io.voyager.samsungbarunhospital.data.DiagLog
import io.voyager.samsungbarunhospital.network.AlertReceiverService

/**
 * Thin foreground service wrapping [BleMeshManager]'s lifecycle. Kept separate from
 * AlertReceiverService (the existing TCP receiver) so a crash or misbehavior in the still-new
 * BLE stack can't take down the TCP call path, and so it simply never starts on devices where
 * [BleMeshManager.isSupported] is false.
 *
 * Reuses AlertReceiverService's "service_channel" notification channel (created there) so both
 * services' persistent notifications live under the same user-facing channel settings.
 */
class BleMeshService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val SERVICE_CHANNEL_ID = "service_channel"

        @JvmStatic
        fun start(context: Context) {
            context.startForegroundService(Intent(context, BleMeshService::class.java))
        }
    }

    /**
     * 서비스 시작 시점에 블루투스가 꺼져 있으면 GATT 서버/클라이언트가 아무것도 못 한 채로
     * "실행 중" 상태에 머물렀다 — 나중에 블루투스를 켜도 복구가 안 돼서, 송신 측은 보냈다고
     * 생각하는데 이 기기는 아무것도 못 받는 상태가 된다. 블루투스 상태 변화를 받아 재기동한다.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    DiagLog.log("BLE상태", "블루투스 켜짐 → BLE 재시작")
                    BleMeshManager.stop()
                    startMesh()
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    DiagLog.log("BLE상태", "⚠ 블루투스 꺼짐 → BLE 중지 (블루투스로 호출 주고받기 불가)")
                    BleMeshManager.stop()
                }
                BluetoothAdapter.STATE_OFF -> BleMeshManager.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent == null) DiagLog.log("BLE상태", "시스템이 BLE 서비스를 재시작함")
        startMesh()
        return START_STICKY
    }

    /**
     * 수신 리스너를 Activity가 아니라 여기서 등록한다 — START_STICKY로 시스템이 서비스만
     * 재시작한 경우(MainActivity.onCreate를 안 거침)에도 받은 호출이 버려지지 않게.
     * peerId도 intent extra 대신 저장소에서 바로 읽는다(재시작 시 intent가 null로 오기 때문).
     */
    private fun startMesh() {
        BleMeshManager.setReceiveListener(object : BleMeshManager.ReceiveListener {
            override fun onPayloadReceived(payload: String, fromPeerId: String) {
                AlertReceiverService.handleIncomingFromBle(payload, fromPeerId)
            }
        })
        BleMeshManager.start(applicationContext, AppSettings.getOrCreateBlePeerId(applicationContext))
    }

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        BleMeshManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("근거리 보조 통신 대기 중")
            .setContentText("Wi-Fi가 다른 환자/간호사 태블릿과 BLE로 통신합니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
