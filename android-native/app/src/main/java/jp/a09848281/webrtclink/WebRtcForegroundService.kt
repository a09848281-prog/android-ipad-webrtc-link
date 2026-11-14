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
 * Owns the foreground lifetime of the WebRTC media session.
 * Diagnostic-only mode deliberately does not initialize WebRTC or access camera/microphone.
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ACTIVE).remove(KEY_GAS_URL).remove(KEY_ROOM).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.action
        try {
            if (action == ACTION_DIAGNOSTIC_ONLY) {
                startForegroundCompat(buildNotification("診断：サービスのみ起動中"), false)
                return START_NOT_STICKY
            }

            if (action != ACTION_START_WEBRTC && action != null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val gasUrl = intent?.getStringExtra(EXTRA_GAS_URL) ?: prefs.getString(KEY_GAS_URL, "").orEmpty()
            val room = intent?.getStringExtra(EXTRA_ROOM) ?: prefs.getString(KEY_ROOM, "").orEmpty()
            val restoring = action == null
            if (restoring && !prefs.getBoolean(KEY_ACTIVE, false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundCompat(buildNotification(if (restoring) "通信を復元中…" else "WebRTC通信を起動中"), true)
            acquireWakeLock()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Foreground service start failed", error)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!gasUrl.startsWith("https://script.google.com/macros/s/") ||
            !gasUrl.endsWith("/exec") || !room.matches(Regex("\\d{6}"))) {
            updateNotification("設定エラー：GAS URLまたは接続コード")
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.edit().putBoolean(KEY_ACTIVE, true).putString(KEY_GAS_URL, gasUrl).putString(KEY_ROOM, room).apply()
        webRtcSession?.close()
        webRtcSession = WebRtcSession(applicationContext, gasUrl, room) { message ->
            updateNotification(message)
        }.also { it.start() }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification, media: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (media) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    private fun buildNotification(status: String): Notification {
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
        private const val TAG = "WebRtcForegroundService"
        const val ACTION_START_WEBRTC = "jp.a09848281.webrtclink.START_WEBRTC"
        const val ACTION_DIAGNOSTIC_ONLY = "jp.a09848281.webrtclink.DIAGNOSTIC_ONLY"
        const val ACTION_STOP = "jp.a09848281.webrtclink.STOP"
        const val EXTRA_GAS_URL = "gas_url"
        const val EXTRA_ROOM = "room_code"
        private const val PREFS = "webrtc_service_state"
        private const val KEY_ACTIVE = "active"
        private const val KEY_GAS_URL = "gas_url"
        private const val KEY_ROOM = "room"
        private const val CHANNEL_ID = "webrtc_session"
        private const val NOTIFICATION_ID = 4102
    }
}
