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
            val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
                result[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted && microphoneGranted && bluetoothGranted) startWebRtcService()
            else status.text = "カメラ・マイク・Bluetooth接続の必要な許可がありません。設定から確認してください。"
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            status.text = if (granted) {
                "位置情報の許可を設定しました。実際の取得はiPadで「位置情報を見る」を押したときだけ行います。"
            } else {
                "位置情報は許可されていません。iPadからの位置情報要求には応答できません。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "WebRTC診断モード。サービス単体テスト済みの場合は②から進めてください。"
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
        val serviceTest = Button(this).apply {
            text = "① サービスだけ起動（安全テスト）"
            setOnClickListener { startServiceOnly() }
        }
        val rtcStart = Button(this).apply {
            text = "② WebRTCを開始（診断）"
            setOnClickListener { requestMediaPermissions() }
        }
        val locationPermission = Button(this).apply {
            text = "位置情報の許可を設定（取得はしません）"
            setOnClickListener {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) status.text = "位置情報の許可は設定済みです。iPadのボタンを押したときだけ取得します。"
                else locationPermissionLauncher.launch(missing.toTypedArray())
            }
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
                text = "Android WebRTC Link - 診断モード"
                textSize = 22f
                setPadding(8, 8, 8, 12)
            })
            addView(TextView(this@MainActivity).apply { text = "GAS WebアプリURL（WebRTCテスト時に使用）" })
            addView(gasUrlInput)
            addView(TextView(this@MainActivity).apply {
                text = "接続コードは②を押した後に表示されます。"
                setPadding(8, 18, 8, 0)
            })
            addView(roomCodeView)
            addView(serviceTest)
            addView(rtcStart)
            addView(locationPermission)
            addView(stop)
            addView(status)
        })
    }

    private fun startServiceOnly() {
        try {
            val intent = Intent(this, WebRtcForegroundService::class.java)
                .setAction(WebRtcForegroundService.ACTION_DIAGNOSTIC_ONLY)
            ContextCompat.startForegroundService(this, intent)
            status.text = "サービス単体テストを開始しました。通知が残るか確認してください。WebRTC・カメラ・マイクは起動していません。"
            roomCodeView.text = "接続コード：テスト前"
        } catch (error: Exception) {
            status.text = "サービス起動失敗: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun requestMediaPermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startWebRtcService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startWebRtcService() {
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
            .setAction(WebRtcForegroundService.ACTION_START_WEBRTC)
            .putExtra(WebRtcForegroundService.EXTRA_GAS_URL, gasUrl)
            .putExtra(WebRtcForegroundService.EXTRA_ROOM, room)
        try {
            ContextCompat.startForegroundService(this, intent)
            roomCodeView.text = "接続コード：$room"
            status.text = "RTC診断を開始しました。番号表示は初期化成功を意味しません。通知の最後のRTC段階を確認してください。"
        } catch (error: Exception) {
            status.text = "WebRTCサービス起動失敗: ${error.javaClass.simpleName}: ${error.message}"
        }
    }
}