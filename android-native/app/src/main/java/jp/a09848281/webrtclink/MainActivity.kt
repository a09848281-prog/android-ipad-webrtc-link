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
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var gasUrlInput: EditText
    private lateinit var roomCodeView: TextView
    private lateinit var pairingCodeView: TextView
    private var microphoneEnabled = true
    private var cameraEnabled = true
    private var webRtcStarted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val microphoneGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted && microphoneGranted) startWebRtcService()
            else status.text = "カメラ・マイク・Bluetooth接続の必要な許可がありません。設定から確認してください。"
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            status.text = if (granted) {
                if (webRtcStarted) "許可を設定しました。位置情報機能を有効にするにはWebRTCを一度停止して再起動してください。取得はiPadのボタンを押したときだけです。"
                else "位置情報の許可を設定しました。実際の取得はiPadで「位置情報を見る」を押したときだけ行います。"
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
            setText(getSharedPreferences("webrtc_link", MODE_PRIVATE).getString("gas_url", ""))
        }
        pairingCodeView = TextView(this).apply {
            text = "ペアリングコード：未発行"
            textSize = 18f
            setPadding(8, 10, 8, 10)
        }
        roomCodeView = TextView(this).apply {
            text = "接続コード：------"
            textSize = 22f
            setPadding(8, 18, 8, 18)
        }
        val pairButton = Button(this).apply {
            text = "🔐 iPadとペアリング（8桁コード発行）"
            setOnClickListener { issuePairingCode() }
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
        val mediaControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 16, 8, 8)
            addView(TextView(this@MainActivity).apply {
                text = "③ 通信中のメディア操作"
                textSize = 18f
                setPadding(0, 8, 0, 8)
            })
            addView(Button(this@MainActivity).apply {
                text = "🎙️ マイクをミュート / 解除"
                setOnClickListener {
                    microphoneEnabled = !microphoneEnabled
                    sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_MIC, WebRtcForegroundService.EXTRA_ENABLED, microphoneEnabled)
                    status.text = if (microphoneEnabled) "Androidマイク：ON" else "Androidマイク：ミュート"
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "📷 カメラ映像を停止 / 再開"
                setOnClickListener {
                    cameraEnabled = !cameraEnabled
                    sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_VIDEO, WebRtcForegroundService.EXTRA_ENABLED, cameraEnabled)
                    status.text = if (cameraEnabled) "Android映像：ON" else "Android映像：停止"
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "🔄 前後カメラ切替"
                setOnClickListener { sendMediaAction(WebRtcForegroundService.ACTION_SWITCH_CAMERA) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "画質（Androidカメラ）"
                setPadding(0, 8, 0, 4)
            })
            addView(Button(this@MainActivity).apply {
                text = "360p / 15fps（低負荷）"
                setOnClickListener { setCameraProfile(640, 360, 15) }
            })
            addView(Button(this@MainActivity).apply {
                text = "720p / 24fps（標準）"
                setOnClickListener { setCameraProfile(1280, 720, 24) }
            })
            addView(Button(this@MainActivity).apply {
                text = "1080p / 30fps（高負荷）"
                setOnClickListener { setCameraProfile(1920, 1080, 30) }
            })
            addView(Button(this@MainActivity).apply {
                text = "🎧 音声のみモード"
                setOnClickListener {
                    cameraEnabled = false
                    sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_VIDEO, WebRtcForegroundService.EXTRA_ENABLED, false)
                    status.text = "音声のみモード：映像を停止しました"
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "🎥 映像のみモード"
                setOnClickListener {
                    microphoneEnabled = false
                    sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_MIC, WebRtcForegroundService.EXTRA_ENABLED, false)
                    status.text = "映像のみモード：マイクをミュートしました"
                }
            })
        }
        val stop = Button(this).apply {
            text = "サービスを停止"
            setOnClickListener {
                startService(Intent(this@MainActivity, WebRtcForegroundService::class.java).setAction(WebRtcForegroundService.ACTION_STOP))
                webRtcStarted = false
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
            addView(pairingCodeView)
            addView(pairButton)
            addView(serviceTest)
            addView(rtcStart)
            addView(locationPermission)
            addView(mediaControls)
            addView(TextView(this@MainActivity).apply {
                text = "🔋 バックグラウンド安定化：設定 → アプリ → WebRTC Link → バッテリー → 制限なし（端末により表示名が異なります）。省電力設定・OSの終了処理により通信継続は保証されません。"
                textSize = 12f
                setPadding(8, 12, 8, 12)
            })
            addView(stop)
            addView(status)
        })
    }

    private fun sendMediaAction(action: String, extraKey: String? = null, enabled: Boolean = true) {
        if (!webRtcStarted) { status.text = "先に② WebRTCを開始してください。"; return }
        val intent = Intent(this, WebRtcForegroundService::class.java).setAction(action)
        if (extraKey != null) intent.putExtra(extraKey, enabled)
        try {
            startService(intent)
        } catch (error: Exception) {
            status.text = "メディア操作を送信できません: ${error.message}"
        }
    }

    private fun setCameraProfile(width: Int, height: Int, fps: Int) {
        if (!webRtcStarted) { status.text = "先に② WebRTCを開始してください。"; return }
        val intent = Intent(this, WebRtcForegroundService::class.java)
            .setAction(WebRtcForegroundService.ACTION_SET_CAMERA_PROFILE)
            .putExtra(WebRtcForegroundService.EXTRA_WIDTH, width)
            .putExtra(WebRtcForegroundService.EXTRA_HEIGHT, height)
            .putExtra(WebRtcForegroundService.EXTRA_FPS, fps)
        try {
            startService(intent)
            status.text = "カメラ設定を要求しました：${width}x${height} / ${fps}fps"
        } catch (error: Exception) {
            status.text = "カメラ設定に失敗しました: ${error.message}"
        }
    }

    private fun issuePairingCode() {
        val gasUrl = gasUrlInput.text.toString().trim().trimEnd('/')
        if (!gasUrl.startsWith("https://script.google.com/macros/s/") || !gasUrl.endsWith("/exec")) {
            status.text = "先に正しいGAS /exec URLを入力してください。"
            return
        }
        val prefs = getSharedPreferences("webrtc_link", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "")
        val authToken = prefs.getString("device_auth_token", null) ?: run {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        val pairBytes = ByteArray(4)
        SecureRandom().nextBytes(pairBytes)
        val pairCode = (10000000L + (java.nio.ByteBuffer.wrap(pairBytes).getInt().toLong() and 0x7fffffffL) % 90000000L).toString()
        status.text = "ペアリングコードを発行しています…"
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("action", "pair_start")
                    .put("deviceId", deviceId)
                    .put("authToken", authToken)
                    .put("pairCode", pairCode)
                    .put("label", Build.MODEL)
                connection = (URL(gasUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val result = JSONObject(response)
                if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "pairing failed"))
                prefs.edit().putString("device_id", deviceId).putString("device_auth_token", authToken)
                    .putString("gas_url", gasUrl).apply()
                runOnUiThread {
                    pairingCodeView.text = "ペアリングコード：$pairCode（5分間有効・1回限り）"
                    status.text = "iPadの端末登録欄に8桁コードを入力してください。"
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "ペアリングコード発行失敗: ${error.message}" }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun startServiceOnly() {
        webRtcStarted = false
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
        val prefs = getSharedPreferences("webrtc_link", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val authToken = prefs.getString("device_auth_token", null)
        if (deviceId.isNullOrBlank() || authToken.isNullOrBlank()) {
            status.text = "先に「iPadとペアリング」を行ってください。"
            return
        }
        val room = (100000..999999).random().toString()
        prefs.edit().putString("gas_url", gasUrl).apply()
        prefs.edit().putString("active_gas_url", gasUrl).putString("active_room", room)
            .putString("active_device_id", deviceId).putString("active_auth_token", authToken).putBoolean("active_session", true).apply()
        val intent = Intent(this, WebRtcForegroundService::class.java)
            .setAction(WebRtcForegroundService.ACTION_START_WEBRTC)
            .putExtra(WebRtcForegroundService.EXTRA_GAS_URL, gasUrl)
            .putExtra(WebRtcForegroundService.EXTRA_ROOM, room)
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(WebRtcForegroundService.EXTRA_AUTH_TOKEN, authToken)
        try {
            ContextCompat.startForegroundService(this, intent)
            webRtcStarted = true
            roomCodeView.text = "接続コード：$room"
            status.text = "RTC診断を開始しました。番号表示は初期化成功を意味しません。通知の最後のRTC段階を確認してください。"
        } catch (error: Exception) {
            prefs.edit().putBoolean("active_session", false).remove("active_gas_url").remove("active_room")
                .remove("active_device_id").remove("active_auth_token").apply()
            webRtcStarted = false
            status.text = "WebRTCサービス起動失敗: ${error.javaClass.simpleName}: ${error.message}"
        }
    }
}