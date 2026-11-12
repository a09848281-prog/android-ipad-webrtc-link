package jp.a09848281.webrtclink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Lifecycle shell only. WebRTC capture/peer logic is deliberately not started here yet.
 * The foreground service is started only after an explicit user action while the Activity is visible.
 */
class WebRtcForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var webRtcSession: WebRtcSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
        } catch (error: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val gasUrl = intent?.getStringExtra(EXTRA_GAS_URL).orEmpty()
        val room = intent?.getStringExtra(EXTRA_ROOM).orEmpty()
        if (!gasUrl.startsWith("https://script.google.com/macros/s/") ||
            !gasUrl.endsWith("/exec") || !room.matches(Regex("\\d{6}"))) {
            stopSelf()
            return START_NOT_STICKY
        }
        webRtcSession?.close()
        webRtcSession = WebRtcSession(applicationContext, gasUrl, room) { message ->
            updateNotification(message)
        }.also { it.start() }
        // START_NOT_STICKY avoids silently resurrecting a half-initialized media session.
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WebRtcSession")
            .apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message.take(100)))
    }

    private fun buildNotification(status: String = "通信サービスを起動中") : Notification {
        val stopIntent = Intent(this, WebRtcForegroundService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("WebRTC Link")
            .setContentText(status)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "停止", pending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WebRTC通信", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "通信中に表示する常駐通知" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        webRtcSession?.close()
        webRtcSession = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "jp.a09848281.webrtclink.START"
        const val ACTION_STOP = "jp.a09848281.webrtclink.STOP"
        const val EXTRA_GAS_URL = "gas_url"
        const val EXTRA_ROOM = "room_code"
        private const val CHANNEL_ID = "webrtc_session"
        private const val NOTIFICATION_ID = 4102
    }
}
