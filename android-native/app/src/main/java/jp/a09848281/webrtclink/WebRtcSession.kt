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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android host-side WebRTC session.
 * Signaling wire format intentionally matches the existing GAS/PWA protocol.
 */
class WebRtcSession(
    private val context: Context,
    private val gasUrl: String,
    private val room: String,
    private val onStatus: (String) -> Unit
) {
    private val closed = AtomicBoolean(false)
    private val network = Executors.newSingleThreadScheduledExecutor()
    private var factory: PeerConnectionFactory? = null
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

    fun start() {
        network.execute {
            try {
                initializePeer()
                startPolling()
                createOffer()
            } catch (t: Throwable) {
                report("WebRTC初期化エラー: ${t.message ?: t.javaClass.simpleName}")
                Log.e(TAG, "start failed", t)
                close()
            }
        }
    }

    private fun initializePeer() {
        report("WebRTCエンジンを初期化中…")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        peer = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer())
            ?: error("PeerConnectionを作成できませんでした")

        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("利用できるカメラがありません")
        capturer = enumerator.createCapturer(cameraName, null) ?: error("カメラを開けません")
        val egl = EglBase.create()
        textureHelper = SurfaceTextureHelper.create("WebRTC-Capture", egl.eglBaseContext)
        videoSource = factory!!.createVideoSource(false)
        capturer!!.initialize(textureHelper, context.applicationContext, videoSource!!.capturerObserver)
        capturer!!.startCapture(1280, 720, 24)
        localVideoTrack = factory!!.createVideoTrack("android_video", videoSource)
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("android_audio", audioSource)
        peer!!.addTrack(localVideoTrack, listOf("webrtc-link"))
        peer!!.addTrack(localAudioTrack, listOf("webrtc-link"))
        report("カメラ・マイクを準備しました。iPadからの接続を待っています…")
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) { report("シグナリング: $state") }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            report(when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "P2P接続成功！"
                PeerConnection.IceConnectionState.CHECKING -> "通信経路を確認中…"
                PeerConnection.IceConnectionState.FAILED -> "P2P接続失敗。ネットワークによってはTURNが必要です。"
                PeerConnection.IceConnectionState.DISCONNECTED -> "通信が一時切断されました"
                else -> "ICE: $state"
            })
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
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
                    val messages = JSONObject(body).optJSONArray("messages") ?: return@try
                    for (i in 0 until messages.length()) {
                        val message = messages.optJSONObject(i) ?: continue
                        val id = message.optString("id")
                        if (id.isNotBlank() && seen.add(id)) handleMessage(message)
                    }
                    if (seen.size > 500) seen.clear()
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
            "candidate" -> {
                val payload = message.optJSONObject("payload") ?: return
                val candidate = IceCandidate(
                    payload.optString("sdpMid").ifBlank { null },
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate")
                )
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
            }
        }
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        network.execute {
            if (closed.get()) return@execute
            var connection: HttpURLConnection? = null
            try {
                val message = JSONObject()
                    .put("room", room).put("from", "android").put("to", "ipad")
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
        try { network.shutdownNow() } catch (_: Throwable) {}
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
        localVideoTrack = null; localAudioTrack = null; videoSource = null; audioSource = null
        peer = null; textureHelper = null; factory = null
    }

    companion object { private const val TAG = "WebRtcSession" }
}
