package jp.a09848281.webrtclink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val mediaGranted = result[Manifest.permission.CAMERA] == true &&
                result[Manifest.permission.RECORD_AUDIO] == true
            if (mediaGranted) startMediaService()
            else status.text = "カメラとマイクの許可が必要です。設定から許可してください。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "準備完了。まずは画面を開いた状態で開始してください。"
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }
        val start = Button(this).apply {
            text = "バックグラウンド通信サービスを開始"
            setOnClickListener { requestMediaPermissions() }
        }
        val stop = Button(this).apply {
            text = "サービスを停止"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WebRtcForegroundService::class.java))
                status.text = "サービスを停止しました。"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 40, 20, 20)
            addView(status)
            addView(start)
            addView(stop)
        })
    }

    private fun requestMediaPermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMediaService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMediaService() {
        val intent = Intent(this, WebRtcForegroundService::class.java)
            .setAction(WebRtcForegroundService.ACTION_START)
        try {
            ContextCompat.startForegroundService(this, intent)
            status.text = "前景サービスを開始しました。通知欄を確認してください。"
        } catch (error: Exception) {
            status.text = "サービスを開始できませんでした: ${error.javaClass.simpleName}"
        }
    }
}
