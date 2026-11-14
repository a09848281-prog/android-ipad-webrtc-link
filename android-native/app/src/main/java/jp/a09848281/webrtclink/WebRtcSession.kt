package jp.a09848281.webrtclink

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
    @Volatile private var reconnectFuture: ScheduledFuture<*>? = null

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
            report(when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    reconnectAttempts = 0
                    reconnectFuture?.cancel(false)
                    reconnectFuture = null
                    "P2P接続成功！"
                }
                PeerConnection.IceConnectionState.CHECKING -> "通信経路を確認中…"
                PeerConnection.IceConnectionState.FAILED -> {
                    scheduleIceRestart(2)
                    "P2P再接続を開始します…"
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    scheduleIceRestart(4)
                    "通信が一時切断されました。再接続を試しています…"
                }
                else -> "ICE: $state"
            })
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
            "answer" -> {
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
            "camera_switch" -> switchCamera()
            "candidate" -> {
                val payload = message.optJSONObject("payload") ?: return
                val candidate = IceCandidate(payload.optString("sdpMid", "0"),
                    payload.optInt("sdpMLineIndex"), payload.optString("candidate"))
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
            }
        }
    }

    private fun switchCamera() {
        val activeCapturer = capturer
        if (activeCapturer == null || closed.get()) {
            sendSignal("camera_switch_result", JSONObject().put("ok", false).put("error", "カメラが起動していません"))
            return
        }
        activeCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                sendSignal("camera_switch_result", JSONObject().put("ok", true).put("facing", if (isFrontCamera) "user" else "environment"))
                report(if (isFrontCamera) "前面カメラに切り替えました" else "背面カメラに切り替えました")
            }
            override fun onCameraSwitchError(errorDescription: String) {
                sendSignal("camera_switch_result", JSONObject().put("ok", false).put("error", errorDescription))
                report("カメラ切り替え失敗: $errorDescription")
            }
        })
    }

    private fun scheduleIceRestart(delaySeconds: Long) {
        if (closed.get() || reconnectFuture != null) return
        reconnectFuture = network.schedule({
            reconnectFuture = null
            if (closed.get()) return@schedule
            val p = peer ?: return@schedule
            val iceState = p.iceConnectionState()
            if (iceState == PeerConnection.IceConnectionState.CONNECTED || iceState == PeerConnection.IceConnectionState.COMPLETED) return@schedule
            if (p.signalingState() != PeerConnection.SignalingState.STABLE) {
                scheduleIceRestart(3)
                return@schedule
            }
            reconnectAttempts += 1
            val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
            p.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    p.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            sendSignal("offer", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description))
                            report("自動再接続Offerを送信しました（試行 $reconnectAttempts）")
                            scheduleIceRestart(minOf(30L, 15L * reconnectAttempts.coerceAtLeast(1)))
                        }
                        override fun onSetFailure(error: String) { fail("再接続Offer設定失敗: $error"); scheduleIceRestart(5) }
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) { fail("再接続Offer作成失敗: $error"); scheduleIceRestart(5) }
                override fun onSetFailure(error: String) {}
            }, constraints)
        }, delaySeconds, TimeUnit.SECONDS)
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

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectFuture?.cancel(false)
        reconnectFuture = null
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