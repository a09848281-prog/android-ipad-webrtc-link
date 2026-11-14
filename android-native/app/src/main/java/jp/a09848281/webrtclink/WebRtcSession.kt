package jp.a09848281.webrtclink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android host-side WebRTC session.
 * Signaling wire format intentionally matches the existing GAS/PWA protocol.
 *
 * Compatibility-first profile for Galaxy A22 / Android 13:
 * software codecs and conservative capture settings are used during initial validation.
 */
class WebRtcSession(
    private val context: Context,
    private val gasUrl: String,
    private val room: String,
    private val onStatus: (String) -> Unit
) {
    private val closed = AtomicBoolean(false)
    private val network = Executors.newSingleThreadScheduledExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private val seen = HashSet<String>()
    private val pendingCandidates = ArrayList<IceCandidate>()
    @Volatile private var remoteDescriptionReady = false
    @Volatile private var pollRunning = false
    @Volatile private var currentStage = "RTC-00: 起動待ち"
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectInFlight = false
    @Volatile private var reconnectTimeout: java.util.concurrent.ScheduledFuture<*>? = null

    private fun stage(message: String) {
        currentStage = message
        Log.i(TAG, message)
        report(message)
    }

    fun start() {
        network.execute {
            try {
                stage("RTC-01: WebRTC SDK初期化")
                initializePeer()
                stage("RTC-11: GAS受信待ちを開始")
                startPolling()
                stage("RTC-12: Offer作成")
                createOffer()
            } catch (t: Throwable) {
                val detail = "${currentStage} / ${t.javaClass.simpleName}: ${t.message ?: "詳細なし"}"
                report("RTC失敗: $detail")
                Log.e(TAG, "WebRTC failed at $currentStage", t)
                close()
            }
        }
    }

    private fun initializePeer() {
        stage("RTC-01: WebRTC SDK初期化")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        stage("RTC-02: EGL初期化")
        eglBase = EglBase.create()

        stage("RTC-03: ソフトウェアCodecでFactory初期化")
        // Avoid device-specific MediaCodec encoder/decoder paths during stability testing.
        val encoder = SoftwareVideoEncoderFactory()
        val decoder = SoftwareVideoDecoderFactory()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        if (factory == null) error("PeerConnectionFactoryを作成できませんでした")

        stage("RTC-04: PeerConnection作成")
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        peer = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer())
            ?: error("PeerConnectionを作成できませんでした")

        stage("RTC-05: カメラ一覧取得")
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val names = enumerator.deviceNames
        if (names.isEmpty()) error("カメラが検出されませんでした")
        val cameraName = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.first()

        stage("RTC-06: カメラCapturer作成")
        capturer = enumerator.createCapturer(cameraName, null) ?: error("カメラCapturerを作成できません")

        stage("RTC-07: 映像ソース作成")
        textureHelper = SurfaceTextureHelper.create("WebRTC-Capture", eglBase!!.eglBaseContext)
            ?: error("SurfaceTextureHelperを作成できません")
        videoSource = factory!!.createVideoSource(false)

        stage("RTC-08: カメラ初期化")
        capturer!!.initialize(textureHelper!!, context.applicationContext, videoSource!!.capturerObserver)

        stage("RTC-09: 低負荷カメラ撮影開始 640x480 / 15fps")
        capturer!!.startCapture(640, 480, 15)

        stage("RTC-10: 映像トラック作成")
        localVideoTrack = factory!!.createVideoTrack("android_video", videoSource)

        stage("RTC-10A: 音声ソース作成")
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("android_audio", audioSource)

        stage("RTC-10B: トラック登録")
        peer!!.addTrack(localVideoTrack!!, listOf("webrtc-link"))
        peer!!.addTrack(localAudioTrack!!, listOf("webrtc-link"))
        stage("RTC-10C: カメラ・マイク準備完了")
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) { report("シグナリング: $state") }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    reconnectAttempts = 0
                    reconnectInFlight = false
                    reconnectTimeout?.cancel(false)
                    reconnectTimeout = null
                    report("P2P接続成功！")
                }
                PeerConnection.IceConnectionState.CHECKING -> report("通信経路を確認中…")
                PeerConnection.IceConnectionState.FAILED -> {
                    report("P2P接続失敗。ICE再起動を試します。")
                    scheduleIceRestart(0)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    report("通信が一時切断されました。復旧を待っています…")
                    scheduleIceRestart(5000)
                }
                else -> report("ICE: $state")
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            val payload = JSONObject().put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)
            sendSignal("candidate", payload)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onTrack(transceiver: RtpTransceiver) {}
    }

    private fun createOffer() {
        val p = peer ?: error("PeerConnectionがありません")
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                p.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal("offer", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description))
                        report("接続コード $room をiPadに入力してください")
                    }
                    override fun onSetFailure(error: String) = fail("Local SDP設定失敗: $error")
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) = fail(error)
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) = fail("Offer作成失敗: $error")
            override fun onSetFailure(error: String) = fail(error)
        }, MediaConstraints())
    }

    private fun scheduleIceRestart(delayMs: Long) {
        if (closed.get() || reconnectInFlight || reconnectAttempts >= 3) return
        network.schedule({ restartIceOffer() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun restartIceOffer() {
        if (closed.get() || reconnectInFlight || reconnectAttempts >= 3) return
        val p = peer ?: return
        if (p.signalingState() != PeerConnection.SignalingState.STABLE) {
            report("再接続を保留：シグナリング状態 ${p.signalingState()}")
            return
        }
        reconnectInFlight = true
        reconnectAttempts++
        report("ICE再起動を試行中（${reconnectAttempts}/3）")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                p.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal("offer", JSONObject().put("type", description.type.canonicalForm())
                            .put("sdp", description.description))
                        report("再接続Offerを送信しました。iPadの応答を待っています。")
                        reconnectTimeout = network.schedule({
                            if (!closed.get() && reconnectInFlight) {
                                reconnectInFlight = false
                                report("再接続応答がありません。接続を確認してください。")
                            }
                        }, 15, TimeUnit.SECONDS)
                    }
                    override fun onSetFailure(error: String) {
                        reconnectInFlight = false
                        fail("ICE再起動Local SDP失敗: $error")
                    }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                reconnectInFlight = false
                fail("ICE再起動Offer失敗: $error")
            }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (closed.get()) return
        network.execute { localAudioTrack?.setEnabled(enabled); report(if (enabled) "Androidマイク：ON" else "Androidマイク：ミュート") }
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (closed.get()) return
        network.execute { localVideoTrack?.setEnabled(enabled); report(if (enabled) "Android映像：ON" else "Android映像：停止") }
    }

    fun switchCamera() {
        if (closed.get()) return
        network.execute {
            val active = capturer ?: return@execute
            active.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    report(if (isFrontCamera) "前面カメラに切り替えました" else "背面カメラに切り替えました")
                }
                override fun onCameraSwitchError(errorDescription: String) {
                    report("カメラ切替失敗: $errorDescription")
                }
            })
        }
    }

    fun setCameraProfile(width: Int, height: Int, fps: Int) {
        if (closed.get()) return
        val safeWidth = width.coerceIn(320, 1920)
        val safeHeight = height.coerceIn(240, 1080)
        val safeFps = fps.coerceIn(10, 30)
        network.execute {
            try {
                capturer?.stopCapture()
                capturer?.startCapture(safeWidth, safeHeight, safeFps)
                report("Androidカメラ設定：${safeWidth}x${safeHeight} / ${safeFps}fps")
            } catch (error: Throwable) {
                report("カメラ設定を適用できません: ${error.message ?: "不明なエラー"}")
            }
        }
    }

    private fun startPolling() {
        network.scheduleWithFixedDelay({
            if (closed.get() || pollRunning) return@scheduleWithFixedDelay
            pollRunning = true
            try {
                val url = URL("$gasUrl?action=poll&room=$room&role=android&_=${System.currentTimeMillis()}")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val messages = JSONObject(body).optJSONArray("messages")
                    if (messages != null) {
                        for (i in 0 until messages.length()) {
                            val message = messages.optJSONObject(i) ?: continue
                            val id = message.optString("id")
                            if (id.isNotBlank() && seen.add(id)) handleMessage(message)
                        }
                        if (seen.size > 500) seen.clear()
                    }
                } finally { connection.disconnect() }
            } catch (t: Throwable) {
                Log.w(TAG, "GAS poll failed", t)
            } finally { pollRunning = false }
        }, 0, 1500, TimeUnit.MILLISECONDS)
    }

    private fun handleMessage(message: JSONObject) {
        if (message.optString("room") != room || message.optString("to") != "android") return
        when (message.optString("type")) {
            "location_request" -> requestOneShotLocation()
            "hangup" -> {
                report("相手側が切断しました。")
                close()
            }
            "answer" -> {
                reconnectTimeout?.cancel(false)
                reconnectTimeout = null
                val payload = message.optJSONObject("payload") ?: return
                val type = SessionDescription.Type.fromCanonicalForm(payload.optString("type", "answer"))
                val description = SessionDescription(type, payload.optString("sdp"))
                peer?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        remoteDescriptionReady = true
                        synchronized(pendingCandidates) {
                            pendingCandidates.forEach { peer?.addIceCandidate(it) }
                            pendingCandidates.clear()
                        }
                        report("iPadの応答を受信しました。P2P接続中…")
                    }
                    override fun onSetFailure(error: String) = fail("Remote SDP設定失敗: $error")
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) = fail(error)
                }, description)
            }
            "candidate" -> {
                val payload = message.optJSONObject("payload") ?: return
                val candidate = IceCandidate(payload.optString("sdpMid", "0"),
                    payload.optInt("sdpMLineIndex"), payload.optString("candidate"))
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
            }
        }
    }

    private fun requestOneShotLocation() {
        if (closed.get()) return
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            sendSignal("location_denied", JSONObject().put("reason", "permission-not-granted"))
            report("iPadから位置情報要求：Androidアプリで位置情報の許可が必要です")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendSignal("location_error", JSONObject().put("reason", "このAndroidバージョンでは現在地取得APIを利用できません"))
            return
        }
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                sendSignal("location_error", JSONObject().put("reason", "位置情報サービスがオフです"))
                return
            }
            report("iPadからの要求により、今回限りの位置情報を取得中…")
            val cancellation = CancellationSignal()
            val timeout = network.schedule({
                cancellation.cancel()
                if (!closed.get()) sendSignal("location_error", JSONObject().put("reason", "位置情報取得がタイムアウトしました"))
            }, 15, TimeUnit.SECONDS)
            manager.getCurrentLocation(provider, cancellation, network) { location: Location? ->
                timeout.cancel(false)
                if (closed.get()) return@getCurrentLocation
                if (location == null) {
                    sendSignal("location_error", JSONObject().put("reason", "現在地を取得できませんでした"))
                } else {
                    val payload = JSONObject()
                        .put("latitude", location.latitude)
                        .put("longitude", location.longitude)
                        .put("accuracy", location.accuracy.toDouble())
                        .put("timestamp", System.currentTimeMillis())
                    sendSignal("location_result", payload)
                    report("iPadの要求に応じて1回限りの位置情報を送信しました")
                }
            }
        } catch (security: SecurityException) {
            sendSignal("location_denied", JSONObject().put("reason", "位置情報の権限がありません"))
        } catch (error: Throwable) {
            sendSignal("location_error", JSONObject().put("reason", error.message ?: "位置情報を取得できませんでした"))
        }
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        sender.execute {
            if (closed.get()) return@execute
            var connection: HttpURLConnection? = null
            try {
                val message = JSONObject().put("room", room).put("from", "android").put("to", "ipad")
                    .put("type", type).put("payload", payload)
                    .put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
                connection = (URL(gasUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                connection.outputStream.use { it.write(message.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
            } catch (t: Throwable) {
                Log.w(TAG, "GAS send failed", t)
                report("GAS送信エラー: ${t.message ?: "接続を確認してください"}")
            } finally { connection?.disconnect() }
        }
    }

    private fun report(message: String) { if (!closed.get()) onStatus(message) }
    private fun fail(message: String) { report(message); Log.e(TAG, message) }

    fun disconnect(onComplete: () -> Unit) {
        if (closed.get()) { onComplete(); return }
        sender.execute {
            try {
                val message = JSONObject().put("room", room).put("from", "android").put("to", "ipad")
                    .put("type", "hangup").put("payload", JSONObject().put("reason", "android-user-disconnect"))
                    .put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
                val connection = (URL(gasUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 5000; readTimeout = 5000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                try {
                    connection.outputStream.use { it.write(message.toString().toByteArray(Charsets.UTF_8)) }
                    connection.inputStream.close()
                } finally { connection.disconnect() }
            } catch (t: Throwable) {
                Log.w(TAG, "Hangup signal failed", t)
            } finally {
                close()
                onComplete()
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectTimeout?.cancel(false)
        reconnectTimeout = null
        Thread({
            try { network.shutdownNow() } catch (_: Throwable) {}
            try { sender.shutdownNow() } catch (_: Throwable) {}
            try { capturer?.stopCapture() } catch (_: Throwable) {}
            try { capturer?.dispose() } catch (_: Throwable) {}
            capturer = null
            try { localVideoTrack?.dispose() } catch (_: Throwable) {}
            try { localAudioTrack?.dispose() } catch (_: Throwable) {}
            try { videoSource?.dispose() } catch (_: Throwable) {}
            try { audioSource?.dispose() } catch (_: Throwable) {}
            try { peer?.close(); peer?.dispose() } catch (_: Throwable) {}
            try { textureHelper?.dispose() } catch (_: Throwable) {}
            try { factory?.dispose() } catch (_: Throwable) {}
            try { eglBase?.release() } catch (_: Throwable) {}
            localVideoTrack = null; localAudioTrack = null; videoSource = null; audioSource = null
            peer = null; textureHelper = null; factory = null; eglBase = null
        }, "WebRTC-Shutdown").start()
    }

    companion object { private const val TAG = "WebRtcSession" }
}