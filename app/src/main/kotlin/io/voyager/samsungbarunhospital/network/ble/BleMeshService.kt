package io.voyager.samsungbarunhospital.network.ble

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

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
        private const val EXTRA_PEER_ID = "peer_id"

        @JvmStatic
        fun start(context: android.content.Context, peerIdHex: String) {
            val intent = Intent(context, BleMeshService::class.java)
            intent.putExtra(EXTRA_PEER_ID, peerIdHex)
            context.startForegroundService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val peerId = intent?.getStringExtra(EXTRA_PEER_ID)
        if (peerId != null) {
            BleMeshManager.start(applicationContext, peerId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
