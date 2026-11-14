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
        window.statusBarColor = Color.rgb(8,14,25)
        window.navigationBarColor = Color.rgb(8,14,25)
        val prefs = getSharedPreferences("webrtc_link", MODE_PRIVATE)
        gasUrlInput = EditText(this).apply {
            hint = "https://script.google.com/macros/s/.../exec"
            setSingleLine(true); textSize = 12f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(prefs.getString("gas_url", ""))
        }
        roomCodeView = TextView(this).apply {
            text = "------"; textSize = 38f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(145,190,255)); letterSpacing = 0.18f; setPadding(0,18,0,18)
        }
        pairingCodeView = makeText("--------", 30f).apply { gravity = Gravity.CENTER; setTextColor(Color.rgb(145,190,255)); letterSpacing = 0.14f }
        status = makeText("ペアリングコードを発行してください。", 12f)
        pairButton = makeButton("ペアリングコードを発行", true) { issuePairingCode() }
        startButton = makeButton("通話コードを発行して待機", true) { requestMediaPermissions() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22,28,22,30)
            setBackgroundColor(Color.rgb(8,14,25))
            addView(makeText("WebRTC Link", 25f))
            addView(makeText("ANDROID DEVICE", 10f).apply { setTextColor(Color.rgb(120,151,195)); letterSpacing = 0.18f })
            val savedUrl = prefs.getString("gas_url", "").orEmpty()
            if (savedUrl.isBlank()) {
                addView(card().apply {
                    addView(makeText("初回設定：GAS WebアプリURL", 13f))
                    addView(gasUrlInput)
                }, margin())
            }
            addView(card().apply {
                addView(makeText("PAIRING CODE", 11f).apply { setTextColor(Color.rgb(145,170,205)); letterSpacing = 0.16f })
                addView(pairingCodeView)
                addView(makeText("iPad側の「端末登録」に入力してください。5分間有効・1回限り。", 11f))
                addView(pairButton)
            }, margin())
            addView(card().apply {
                addView(makeText("CALL CODE", 11f).apply { setTextColor(Color.rgb(145,170,205)); letterSpacing = 0.16f })
                addView(roomCodeView)
                addView(makeText("iPadから接続すると通話が開始されます。", 11f))
                addView(startButton)
            }, margin())
            addView(card().apply {
                addView(makeText("接続状態", 14f))
                addView(status)
            }, margin())
        }
        setContentView(ScrollView(this).apply { addView(root) })
        // Restore the existing call state when the user opens the Activity during a call.
        val savedRoom = prefs.getString("active_room", "").orEmpty()
        if (prefs.getBoolean("active_session", false) && savedRoom.matches(Regex("\\d{6}"))) {
            webRtcStarted = true
            roomCodeView.text = savedRoom
            startButton.text = "● 通話待機中（再起動しない）"
            startButton.isEnabled = false
            showStatus("通話サービスはバックグラウンドで動作中です。この画面を開いても通信は再起動しません。", false)
        }
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
        val perms=buildList{add(Manifest.permission.CAMERA);add(Manifest.permission.RECORD_AUDIO);add(Manifest.permission.ACCESS_COARSE_LOCATION);add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}
        val missing=perms.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty())startWebRtcService() else permissionLauncher.launch(missing.toTypedArray())
    }
    private fun startWebRtcService() {
        val existingPrefs = getSharedPreferences("webrtc_link", MODE_PRIVATE)
        val existingRoom = existingPrefs.getString("active_room", "").orEmpty()
        if (existingPrefs.getBoolean("active_session", false) && existingRoom.matches(Regex("\\d{6}"))) {
            webRtcStarted = true
            roomCodeView.text = existingRoom
            startButton.text = "● 通話待機中（再起動しない）"
            startButton.isEnabled = false
            showStatus("既存の通話サービスを維持しています。新しい接続を開始しません。", false)
            return
        }
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