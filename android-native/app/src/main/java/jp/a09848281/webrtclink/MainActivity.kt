package jp.a09848281.webrtclink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var gasUrlInput: EditText
    private lateinit var roomCodeView: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val microphoneGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted && microphoneGranted) startMediaService()
            else status.text = "カメラとマイクの許可が必要です。設定から許可してください。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "GAS URLを入力して、開始してください。"
            textSize = 16f
            setPadding(8, 16, 8, 16)
        }
        gasUrlInput = EditText(this).apply {
            hint = "GAS WebアプリURL（https://script.google.com/macros/s/.../exec）"
            setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getString("gas_url", ""))
        }
        roomCodeView = TextView(this).apply {
            text = "接続コード：------"
            textSize = 22f
            setPadding(8, 18, 8, 18)
        }
        val start = Button(this).apply {
            text = "WebRTC通信を開始"
            setOnClickListener { requestMediaPermissions() }
        }
        val stop = Button(this).apply {
            text = "サービスを停止"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WebRtcForegroundService::class.java))
                status.text = "サービスを停止しました。"
                roomCodeView.text = "接続コード：------"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 32, 20, 20)
            addView(TextView(this@MainActivity).apply {
                text = "Android WebRTC Link"
                textSize = 22f
                setPadding(8, 8, 8, 12)
            })
            addView(TextView(this@MainActivity).apply { text = "① GAS WebアプリURL" })
            addView(gasUrlInput)
            addView(TextView(this@MainActivity).apply {
                text = "② 開始後、この6桁コードをiPad側に入力"
                setPadding(8, 18, 8, 0)
            })
            addView(roomCodeView)
            addView(start)
            addView(stop)
            addView(status)
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
        val gasUrl = gasUrlInput.text.toString().trim().trimEnd('/')
        val valid = gasUrl.startsWith("https://script.google.com/macros/s/") &&
            gasUrl.endsWith("/exec") && !gasUrl.contains("?")
        if (!valid) {
            status.text = "GAS WebアプリURLを確認してください。末尾が /exec のURLを入力します。"
            return
        }
        val room = (100000..999999).random().toString()
        getPreferences(MODE_PRIVATE).edit().putString("gas_url", gasUrl).apply()
        val intent = Intent(this, WebRtcForegroundService::class.java)
            .setAction(WebRtcForegroundService.ACTION_START)
            .putExtra(WebRtcForegroundService.EXTRA_GAS_URL, gasUrl)
            .putExtra(WebRtcForegroundService.EXTRA_ROOM, room)
        try {
            ContextCompat.startForegroundService(this, intent)
            roomCodeView.text = "接続コード：$room"
            status.text = "サービスを開始しました。通知欄で状態を確認できます。"
        } catch (error: Exception) {
            status.text = "サービスを開始できませんでした: ${error.javaClass.simpleName}"
        }
    }
}
