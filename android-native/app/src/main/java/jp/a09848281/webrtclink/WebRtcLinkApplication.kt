package jp.a09848281.webrtclink

import android.app.Application
import android.util.Log

class WebRtcLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RtcDiagnostics.detectUnexpectedExit(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                RtcDiagnostics.error(this, "UNCAUGHT_EXCEPTION", error)
                Log.e(TAG, "Uncaught exception on ${thread.name}", error)
            } catch (diagnosticError: Throwable) {
                Log.e(TAG, "Could not save crash diagnostics", diagnosticError)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "WebRtcLinkApplication"
    }
}
