package com.bmo.mcpandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AndroidTunnelService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_CONNECT = "com.bmo.mcpandroid.CONNECT"
        const val ACTION_DISCONNECT = "com.bmo.mcpandroid.DISCONNECT"
        private const val CHANNEL = "bmo-android-tunnel"
        private const val NOTIFICATION_ID = 71
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    private lateinit var client: OkHttpClient
    private var socket: WebSocket? = null
    private var stoppedByUser = false
    private var reconnectMs = 1500L
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val audioBusy = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Inicializando túnel"))
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stoppedByUser = true
                TunnelState.status = "DISCONNECTED"
                socket?.close(1000, "user")
                socket = null
                stopSelf()
            }
            else -> {
                stoppedByUser = false
                connect()
            }
        }
        return START_STICKY
    }

    private fun connect() {
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        val url = prefs.getString("gateway_url", "")?.trim().orEmpty()
        if (!url.startsWith("wss://")) {
            TunnelState.status = "FAILED"
            TunnelState.lastError = "O gateway deve usar wss://"
            updateNotification("URL inválida")
            return
        }
        if (socket != null) return
        TunnelState.status = "CONNECTING"
        TunnelState.lastError = null
        updateNotification("Conectando")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectMs = 1500L
                TunnelState.status = "CONNECTED"
                updateNotification("Conectado ao MCP")
                authenticate(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 512_000) return
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                TunnelState.status = "RECONNECTING"
                TunnelState.lastError = t.javaClass.simpleName
                updateNotification("Reconectando")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (!stoppedByUser) scheduleReconnect()
            }
        })
    }

    private fun authenticate(webSocket: WebSocket) {
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        val credential = CredentialStore(this).load()
        val pairingCode = prefs.getString("pairing_code", null)
        val payload = JSONObject()
        if (credential.isNullOrBlank()) {
            payload.put("type", "pair")
            payload.put("pairing_code", pairingCode ?: "")
        } else {
            payload.put("type", "hello")
            payload.put("credential", credential)
        }
        payload.put("device_id", deviceId)
        payload.put("platform", "android")
        payload.put("protocol_version", 1)
        payload.put("app_version", BuildConfig.VERSION_NAME)
        payload.put("capabilities", JSONObject()
            .put("camera", packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            .put("microphone", packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            .put("speaker", true)
            .put("overlay", true)
            .put("vibrate", true))
        webSocket.send(payload.toString())
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val message = try { JSONObject(text) } catch (_: Throwable) { return }
        when (message.optString("type")) {
            "paired" -> {
                val credential = message.optString("credential")
                if (credential.isNotBlank()) {
                    CredentialStore(this).save(credential)
                    getSharedPreferences("bridge", MODE_PRIVATE).edit()
                        .remove("pairing_code")
                        .apply()
                    webSocket.send(JSONObject()
                        .put("type", "hello")
                        .put("device_id", getSharedPreferences("bridge", MODE_PRIVATE).getString("device_id", ""))
                        .put("credential", credential)
                        .put("platform", "android")
                        .put("protocol_version", 1)
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("capabilities", JSONObject()
                            .put("camera", packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                            .put("microphone", packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
                            .put("speaker", true)
                            .put("overlay", true)
                            .put("vibrate", true))
                        .toString())
                }
            }
            "ready" -> {
                TunnelState.status = "READY"
                updateNotification("MCP conectado")
            }
            "command" -> worker.execute { executeCommand(webSocket, message) }
            "error" -> {
                val error = message.optString("error", "gateway error")
                TunnelState.lastError = error
                if (error.contains("AUTH_FAILED") || error.contains("PAIRING_INVALID")) {
                    CredentialStore(this).clear()
                }
            }
        }
    }

    private fun executeCommand(webSocket: WebSocket, message: JSONObject) {
        val id = message.optString("id")
        val op = message.optString("op")
        val args = message.optJSONObject("args") ?: JSONObject()
        val out = JSONObject().put("type", "result").put("reply_to", id)
        try {
            when (op) {
                "ping" -> out.put("ok", true).put("payload", JSONObject().put("pong", true).put("ts", System.currentTimeMillis()))
                "capture_frame" -> {
                    val frame = FrameStore.snapshot() ?: error("CAMERA_FRAME_UNAVAILABLE")
                    val ageMs = System.currentTimeMillis() - frame.capturedAtMs
                    if (ageMs > 2500) error("CAMERA_FRAME_STALE")
                    if (frame.jpeg.size > 2_500_000) error("FRAME_TOO_LARGE")
                    out.put("ok", true).put("payload", JSONObject()
                        .put("encoding", "base64")
                        .put("format", "jpeg")
                        .put("width", frame.width)
                        .put("height", frame.height)
                        .put("captured_at_ms", frame.capturedAtMs)
                        .put("age_ms", ageMs)
                        .put("data", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP)))
                }
                "draw_circle" -> {
                    val ttl = args.optLong("ttl_ms", 8000).coerceIn(500, 120_000)
                    OverlayStore.add(CircleOverlay(
                        args.optDouble("x", 0.5).toFloat(),
                        args.optDouble("y", 0.5).toFloat(),
                        args.optDouble("radius", 0.08).toFloat(),
                        args.optString("label").takeIf { it.isNotBlank() },
                        System.currentTimeMillis() + ttl
                    ))
                    out.put("ok", true).put("payload", JSONObject().put("drawn", true))
                }
                "clear_overlay" -> {
                    OverlayStore.clear()
                    out.put("ok", true).put("payload", JSONObject().put("cleared", true))
                }
                "speak" -> {
                    val textValue = args.optString("text").take(4000)
                    if (textValue.isBlank()) error("TEXT_REQUIRED")
                    if (!ttsReady) error("TTS_NOT_READY")
                    val code = tts?.speak(textValue, TextToSpeech.QUEUE_FLUSH, null, "mcp-" + System.currentTimeMillis())
                    if (code == TextToSpeech.ERROR) error("TTS_FAILED")
                    out.put("ok", true).put("payload", JSONObject().put("speaking", true))
                }
                "vibrate" -> {
                    val ms = args.optLong("duration_ms", 250).coerceIn(20, 5000)
                    val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(ms)
                    }
                    out.put("ok", true).put("payload", JSONObject().put("vibrating", true))
                }
                "record_audio" -> out.put("ok", true).put("payload", recordAudio(args.optInt("duration_ms", 3000)))
                else -> error("UNSUPPORTED_COMMAND:" + op)
            }
        } catch (t: Throwable) {
            out.put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
        }
        webSocket.send(out.toString())
    }

    private fun recordAudio(requestedMs: Int): JSONObject {
        if (!audioBusy.compareAndSet(false, true)) error("MICROPHONE_BUSY")
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                error("MICROPHONE_PERMISSION_REQUIRED")
            }
            val durationMs = requestedMs.coerceIn(250, 10_000)
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 4096)
            )
            val totalBytes = sampleRate * 2 * durationMs / 1000
            val data = ByteArray(totalBytes)
            var offset = 0
            recorder.startRecording()
            try {
                while (offset < data.size) {
                    val read = recorder.read(data, offset, min(4096, data.size - offset))
                    if (read <= 0) break
                    offset += read
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
            return JSONObject()
                .put("encoding", "base64")
                .put("format", "pcm_s16le")
                .put("sample_rate", sampleRate)
                .put("channels", 1)
                .put("duration_ms", durationMs)
                .put("data", Base64.encodeToString(data.copyOf(offset), Base64.NO_WRAP))
        } finally {
            audioBusy.set(false)
        }
    }

    private fun scheduleReconnect() {
        if (stoppedByUser) return
        val delay = reconnectMs
        reconnectMs = (reconnectMs * 2).coerceAtMost(30_000)
        handler.postDelayed({ if (!stoppedByUser && socket == null) connect() }, delay)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "B.M.O. Android Tunnel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.presence_online)
        .setContentTitle("B.M.O. Android")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
            ttsReady = true
        } else {
            ttsReady = false
        }
    }

    override fun onDestroy() {
        stoppedByUser = true
        socket?.close(1000, "service destroyed")
        socket = null
        tts?.shutdown()
        worker.shutdownNow()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
