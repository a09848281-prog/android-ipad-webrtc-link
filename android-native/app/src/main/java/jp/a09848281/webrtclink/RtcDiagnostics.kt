package jp.a09848281.webrtclink

import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the last RTC stage on disk so it survives a native crash/process kill.
 * Does not store the GAS URL, FCM token, or media data.
 */
object RtcDiagnostics {
    private const val PREFS = "webrtc_rtc_diagnostics"
    private const val KEY_STAGE = "last_stage"
    private const val KEY_STATUS = "last_status"
    private const val KEY_ERROR = "last_error"
    private const val KEY_STACK = "last_stack"
    private const val KEY_UPDATED = "last_updated"
    private const val KEY_STARTED = "session_started"
    private const val KEY_ROOM = "session_room"
    private const val KEY_SERVICE_ACTIVE = "service_active"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beginSession(context: Context, room: String) {
        prefs(context).edit()
            .putString(KEY_STAGE, "SERVICE_REQUESTED: Foreground Service起動要求")
            .putString(KEY_STATUS, "WebRTCサービスを起動しています。")
            .putString(KEY_ROOM, room)
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .remove(KEY_STACK)
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .apply()
    }

    fun stage(context: Context, message: String) {
        prefs(context).edit()
            .putString(KEY_STAGE, message)
            .putString(KEY_STATUS, message)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun status(context: Context, message: String) {
        prefs(context).edit()
            .putString(KEY_STATUS, message)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun markServiceActive(context: Context, active: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SERVICE_ACTIVE, active)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun error(context: Context, stage: String, message: String, stackTrace: String? = null) {
        val editor = prefs(context).edit()
            .putString(KEY_STAGE, stage)
            .putString(KEY_STATUS, message)
            .putString(KEY_ERROR, message)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
        if (!stackTrace.isNullOrBlank()) editor.putString(KEY_STACK, stackTrace.take(6000))
        editor.apply()
    }

    fun error(context: Context, stage: String, throwable: Throwable) {
        error(
            context,
            stage,
            "${throwable.javaClass.simpleName}: ${throwable.message ?: "詳細なし"}",
            throwable.stackTraceToString()
        )
    }

    /**
     * If Android killed the process without calling Service.onDestroy(), the
     * active flag remains set. The next app launch records this as a likely
     * unexpected process exit (not as proof of the exact native crash cause).
     */
    fun detectUnexpectedExit(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SERVICE_ACTIVE, false)) {
            val lastStage = p.getString(KEY_STAGE, "不明") ?: "不明"
            error(
                context,
                "PROCESS_RESTART",
                "前回のプロセスがサービス稼働中に終了した可能性があります。最後の段階: $lastStage"
            )
        }
        markServiceActive(context, false)
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val stage = p.getString(KEY_STAGE, "まだRTCを開始していません") ?: "不明"
        val status = p.getString(KEY_STATUS, "なし") ?: "なし"
        val error = p.getString(KEY_ERROR, null)
        val updated = p.getLong(KEY_UPDATED, 0L)
        val time = if (updated > 0) DateFormat.getDateTimeInstance().format(Date(updated)) else "なし"
        return buildString {
            append("前回の診断段階: ").append(stage)
            append("\n状態: ").append(status)
            if (!error.isNullOrBlank()) append("\nエラー: ").append(error)
            append("\n更新: ").append(time)
        }
    }

    fun snapshot(context: Context): String {
        val p = prefs(context)
        val stack = p.getString(KEY_STACK, "記録なし") ?: "記録なし"
        val room = p.getString(KEY_ROOM, "なし") ?: "なし"
        return buildString {
            appendLine("WebRTC Link 診断ログ")
            appendLine("端末: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Room: $room")
            appendLine("最後の段階: ${p.getString(KEY_STAGE, "なし")}")
            appendLine("最後の状態: ${p.getString(KEY_STATUS, "なし")}")
            appendLine("エラー: ${p.getString(KEY_ERROR, "なし")}")
            appendLine("更新時刻: ${p.getLong(KEY_UPDATED, 0L)}")
            appendLine("Stack trace:")
            append(stack.take(6000))
        }
    }
}
