package jp.a09848281.webrtclink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
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
    private lateinit var startButton: Button
    private lateinit var pairButton: Button
    private var microphoneEnabled = true
    private var cameraEnabled = true
    private var webRtcStarted = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val camera = result[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (camera && mic) startWebRtcService() else showStatus("カメラとマイクの許可が必要です。設定から許可してください。", true)
    }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        showStatus(if (ok) "位置情報の許可が完了しました。取得はiPadから要求されたときだけです。" else "位置情報は許可されていません。", !ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11,18,32)
        window.navigationBarColor = Color.rgb(11,18,32)
        gasUrlInput = EditText(this).apply {
            hint = "https://script.google.com/macros/s/.../exec"; setSingleLine(true); textSize = 13f
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(getSharedPreferences("webrtc_link", MODE_PRIVATE).getString("gas_url", ""))
        }
        roomCodeView = TextView(this).apply { text = "------"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.rgb(145,190,255)); setPadding(0,12,0,12) }
        pairingCodeView = makeText("ペアリングコード：未発行", 13f)
        status = makeText("最初にiPadとペアリングしてください。", 13f)
        pairButton = makeButton("🔐  iPadとペアリング", true) { issuePairingCode() }
        startButton = makeButton("▶  待機を開始", true) { requestMediaPermissions() }
        val stop = makeButton("■  待機・通信を停止", false) {
            startService(Intent(this, WebRtcForegroundService::class.java).setAction(WebRtcForegroundService.ACTION_STOP))
            webRtcStarted = false; startButton.isEnabled = true; startButton.text = "▶  待機を開始"; roomCodeView.text = "------"
            showStatus("待機を停止しました。", false)
        }
        val location = makeButton("📍  位置情報の許可を設定", false) {
            val perms = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) showStatus("許可済みです。取得はiPadから要求されたときだけです。", false) else locationPermissionLauncher.launch(missing.toTypedArray())
        }
        val controls = card().apply {
            addView(makeText("通信中の操作", 17f))
            addView(makeButton("🎙️  マイクをミュート／解除", false) {
                microphoneEnabled = !microphoneEnabled
                sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_MIC, WebRtcForegroundService.EXTRA_ENABLED, microphoneEnabled)
                showStatus(if (microphoneEnabled) "マイク：ON" else "マイク：ミュート", false)
            })
            addView(makeButton("📷  カメラ映像を停止／再開", false) {
                cameraEnabled = !cameraEnabled
                sendMediaAction(WebRtcForegroundService.ACTION_TOGGLE_VIDEO, WebRtcForegroundService.EXTRA_ENABLED, cameraEnabled)
                showStatus(if (cameraEnabled) "カメラ：ON" else "カメラ：停止", false)
            })
            addView(makeButton("🔄  前後カメラを切り替え", false) { sendMediaAction(WebRtcForegroundService.ACTION_SWITCH_CAMERA) })
            addView(makeText("画質設定", 14f))
            addView(makeButton("軽量　360p / 15fps", false) { setCameraProfile(640,360,15) })
            addView(makeButton("標準　720p / 24fps", false) { setCameraProfile(1280,720,24) })
            addView(makeButton("高画質　1080p / 30fps", false) { setCameraProfile(1920,1080,30) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16,22,16,24); setBackgroundColor(Color.rgb(11,18,32))
            addView(makeText("WebRTC Link", 27f))
            addView(makeText("Android  ⇄  iPad", 13f))
            addView(card().apply {
                addView(makeText("接続の準備", 17f)); addView(makeText("GAS WebアプリURL", 12f)); addView(gasUrlInput)
                addView(pairButton); addView(pairingCodeView)
            }, margin())
            addView(card().apply {
                addView(makeText("接続待機", 17f))
                addView(makeText("① ペアリング　② 待機を開始　③ iPadから接続", 12f))
                addView(roomCodeView); addView(startButton); addView(stop)
            }, margin())
            addView(controls, margin())
            addView(card().apply {
                addView(makeText("位置情報", 17f))
                addView(makeText("自動取得しません。iPadから要求されたときだけ1回取得します。", 12f))
                addView(location)
            }, margin())
            addView(card().apply { addView(makeText("状態",17f)); addView(status) }, margin())
            addView(makeText("ロック画面での待機は、通信中の常駐通知が表示されている間に利用できます。省電力設定やOSによる終了時は継続できない場合があります。", 11f))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun margin() = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,10,0,10) }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(15,12,15,12)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(21,31,48)); cornerRadius = 18f; setStroke(1,Color.rgb(43,58,80))
        }
    }
    private fun makeText(value: String, size: Float) = TextView(this).apply {
        text=value; textSize=size; setTextColor(Color.rgb(225,235,248)); setPadding(2,6,2,6)
    }
    private fun makeButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text=label; isAllCaps=false; textSize=14f
        backgroundTintList=android.content.res.ColorStateList.valueOf(if(primary) Color.rgb(55,112,224) else Color.rgb(42,59,83))
        setTextColor(Color.WHITE); setOnClickListener { action() }
    }
    private fun showStatus(message: String, error: Boolean) {
        status.text=message; status.setTextColor(if(error) Color.rgb(255,145,155) else Color.rgb(160,225,195))
    }

    private fun sendMediaAction(action: String, extraKey: String? = null, enabled: Boolean = true) {
        if (!webRtcStarted) { showStatus("先に「待機を開始」を押してください。", true); return }
        val intent=Intent(this,WebRtcForegroundService::class.java).setAction(action)
        if(extraKey!=null) intent.putExtra(extraKey,enabled)
        try { startService(intent) } catch(e:Exception) { showStatus("操作を送信できません："+e.message,true) }
    }
    private fun setCameraProfile(width:Int,height:Int,fps:Int) {
        if(!webRtcStarted){showStatus("先に「待機を開始」を押してください。",true);return}
        val intent=Intent(this,WebRtcForegroundService::class.java).setAction(WebRtcForegroundService.ACTION_SET_CAMERA_PROFILE)
            .putExtra(WebRtcForegroundService.EXTRA_WIDTH,width).putExtra(WebRtcForegroundService.EXTRA_HEIGHT,height).putExtra(WebRtcForegroundService.EXTRA_FPS,fps)
        try { startService(intent); showStatus("画質変更を要求しました："+width+"x"+height+" / "+fps+"fps",false) }
        catch(e:Exception){showStatus("画質変更に失敗しました："+e.message,true)}
    }
    private fun issuePairingCode() {
        val gasUrl=gasUrlInput.text.toString().trim().trimEnd('/')
        if(!gasUrl.startsWith("https://script.google.com/macros/s/")||!gasUrl.endsWith("/exec")){showStatus("GAS /exec URLを確認してください。",true);return}
        pairButton.isEnabled=false
        val prefs=getSharedPreferences("webrtc_link",MODE_PRIVATE)
        val deviceId=prefs.getString("device_id",null)?:UUID.randomUUID().toString().replace("-","")
        val authToken=prefs.getString("device_auth_token",null)?:run{val b=ByteArray(32);SecureRandom().nextBytes(b);Base64.encodeToString(b,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
        val b=ByteArray(4);SecureRandom().nextBytes(b)
        val pairCode=(10000000L+(java.nio.ByteBuffer.wrap(b).getInt().toLong() and 0x7fffffffL)%90000000L).toString()
        showStatus("ペアリングコードを発行しています…",false)
        Thread {
            var c:HttpURLConnection?=null
            try {
                val body=JSONObject().put("action","pair_start").put("deviceId",deviceId).put("authToken",authToken).put("pairCode",pairCode).put("label",Build.MODEL)
                c=(URL(gasUrl).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=10000;readTimeout=10000;setRequestProperty("Content-Type","text/plain; charset=utf-8")}
                c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
                val result=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                if(!result.optBoolean("ok")) throw IllegalStateException(result.optString("error","pairing failed"))
                prefs.edit().putString("device_id",deviceId).putString("device_auth_token",authToken).putString("gas_url",gasUrl).apply()
                runOnUiThread{pairingCodeView.text="8桁コード："+pairCode+"（5分間有効・1回限り）";showStatus("iPadの端末登録欄に8桁コードを入力してください。",false);pairButton.isEnabled=true}
            } catch(e:Exception){runOnUiThread{showStatus("ペアリング失敗："+e.message,true);pairButton.isEnabled=true}}
            finally{c?.disconnect()}
        }.start()
    }
    private fun requestMediaPermissions() {
        val perms=buildList{add(Manifest.permission.CAMERA);add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}
        val missing=perms.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty())startWebRtcService() else permissionLauncher.launch(missing.toTypedArray())
    }
    private fun startWebRtcService() {
        val gasUrl=gasUrlInput.text.toString().trim().trimEnd('/')
        if(!gasUrl.startsWith("https://script.google.com/macros/s/")||!gasUrl.endsWith("/exec")||gasUrl.contains("?")){showStatus("GAS WebアプリURLを確認してください。末尾は /exec です。",true);return}
        val prefs=getSharedPreferences("webrtc_link",MODE_PRIVATE)
        val deviceId=prefs.getString("device_id",null);val token=prefs.getString("device_auth_token",null)
        if(deviceId.isNullOrBlank()||token.isNullOrBlank()){showStatus("先にiPadとペアリングしてください。",true);return}
        val room=(100000..999999).random().toString()
        prefs.edit().putString("gas_url",gasUrl).putString("active_gas_url",gasUrl).putString("active_room",room).putString("active_device_id",deviceId).putString("active_auth_token",token).putBoolean("active_session",true).apply()
        val intent=Intent(this,WebRtcForegroundService::class.java).setAction(WebRtcForegroundService.ACTION_START_WEBRTC)
            .putExtra(WebRtcForegroundService.EXTRA_GAS_URL,gasUrl).putExtra(WebRtcForegroundService.EXTRA_ROOM,room)
            .putExtra(WebRtcForegroundService.EXTRA_DEVICE_ID,deviceId).putExtra(WebRtcForegroundService.EXTRA_AUTH_TOKEN,token)
        try {
            ContextCompat.startForegroundService(this,intent);webRtcStarted=true;roomCodeView.text=room
            startButton.text="● 待機中";startButton.isEnabled=false
            showStatus("待機を開始しました。通知が表示されたらiPadから接続してください。",false)
        } catch(e:Exception) {
            prefs.edit().putBoolean("active_session",false).remove("active_gas_url").remove("active_room").remove("active_device_id").remove("active_auth_token").apply()
            webRtcStarted=false;showStatus("待機開始に失敗しました："+e.javaClass.simpleName+"："+e.message,true)
        }
    }
}