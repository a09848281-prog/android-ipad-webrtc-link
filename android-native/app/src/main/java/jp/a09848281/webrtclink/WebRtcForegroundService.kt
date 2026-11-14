package jp.a09848281.webrtclink

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
    private var activeRoom: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !getSharedPreferences("webrtc_link", MODE_PRIVATE).getBoolean("active_session", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val activeSession = webRtcSession
            if (activeSession != null) activeSession.disconnect { clearPersistedSession(); stopSelf() }
            else { clearPersistedSession(); stopSelf() }
            return START_NOT_STICKY
        }

        val action = intent?.action
        if (action == ACTION_START_WEBRTC && webRtcSession != null) {
            val requestedRoom = intent?.getStringExtra(EXTRA_ROOM)
            if (!requestedRoom.isNullOrBlank() && requestedRoom == activeRoom) {
                // The Activity may be reopened while the foreground call is already alive.
                // Never tear down and recreate the active PeerConnection for the same room.
                updateNotification("通話待機中：$requestedRoom")
                return START_STICKY
            }
        }
        when (action) {
            ACTION_TOGGLE_MIC -> { webRtcSession?.setMicrophoneEnabled(intent?.getBooleanExtra(EXTRA_ENABLED, true) ?: true); return START_STICKY }
            ACTION_TOGGLE_VIDEO -> { webRtcSession?.setCameraEnabled(intent?.getBooleanExtra(EXTRA_ENABLED, true) ?: true); return START_STICKY }
            ACTION_SWITCH_CAMERA -> { webRtcSession?.switchCamera(); return START_STICKY }
            ACTION_SET_CAMERA_PROFILE -> {
                webRtcSession?.setCameraProfile(
                    intent?.getIntExtra(EXTRA_WIDTH, 640) ?: 640,
                    intent?.getIntExtra(EXTRA_HEIGHT, 480) ?: 480,
                    intent?.getIntExtra(EXTRA_FPS, 15) ?: 15
                )
                return START_STICKY
            }
        }
        try {
            if (action == ACTION_DIAGNOSTIC_ONLY) {
                startForegroundCompat(buildNotification("診断：サービスのみ起動中"), false)
                return START_NOT_STICKY
            }

            if (action != ACTION_START_WEBRTC && action != null) {
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundCompat(buildNotification("WebRTC通信を起動中"), true)
            acquireWakeLock()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Foreground service start failed", error)
            clearPersistedSession()
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("webrtc_link", MODE_PRIVATE)
        val gasUrl = intent?.getStringExtra(EXTRA_GAS_URL) ?: prefs.getString("active_gas_url", "").orEmpty()
        val room = intent?.getStringExtra(EXTRA_ROOM) ?: prefs.getString("active_room", "").orEmpty()
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID) ?: prefs.getString("active_device_id", "").orEmpty()
        val authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN) ?: prefs.getString("active_auth_token", "").orEmpty()
        if (!gasUrl.startsWith("https://script.google.com/macros/s/") ||
            !gasUrl.endsWith("/exec") || !room.matches(Regex("\\d{6}")) ||
            !deviceId.matches(Regex("[A-Za-z0-9_-]{16,80}")) || !authToken.matches(Regex("[A-Za-z0-9_-]{40,100}"))) {
            updateNotification("設定エラー：GAS URLまたは接続コード")
            clearPersistedSession()
            stopSelf()
            return START_NOT_STICKY
        }

        webRtcSession?.close()
        activeRoom = room
        webRtcSession = WebRtcSession(applicationContext, gasUrl, room, deviceId, authToken) { message ->
            updateNotification(message)
            if (message == "相手側が切断しました。") { activeRoom = null; clearPersistedSession(); stopSelf() }
        }.also { it.start() }
        return START_STICKY
    }

    private fun clearPersistedSession() {
        getSharedPreferences("webrtc_link", MODE_PRIVATE).edit()
            .putBoolean("active_session", false)
            .remove("active_gas_url").remove("active_room")
            .remove("active_device_id").remove("active_auth_token").apply()
    }

    private fun startForegroundCompat(notification: Notification, media: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = if (media) {
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
        activeRoom = null
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
        const val ACTION_TOGGLE_MIC = "jp.a09848281.webrtclink.TOGGLE_MIC"
        const val ACTION_TOGGLE_VIDEO = "jp.a09848281.webrtclink.TOGGLE_VIDEO"
        const val ACTION_SWITCH_CAMERA = "jp.a09848281.webrtclink.SWITCH_CAMERA"
        const val ACTION_SET_CAMERA_PROFILE = "jp.a09848281.webrtclink.SET_CAMERA_PROFILE"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_GAS_URL = "gas_url"
        const val EXTRA_ROOM = "room_code"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        private const val CHANNEL_ID = "webrtc_session"
        private const val NOTIFICATION_ID = 4102
    }
}
