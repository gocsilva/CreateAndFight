package com.bmo.mcpandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private var autoConnectAttempted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startCameraIfAllowed()
        maybeAutoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val overlay = GuideOverlayView(this)

        val gateway = EditText(this).apply {
            hint = "wss://mcp.seudominio.com/android"
            setText(prefs.getString("gateway_url", "") ?: "")
            setSingleLine(true)
        }
        val pairing = EditText(this).apply {
            hint = "Código de pareamento (6 dígitos)"
            setSingleLine(true)
        }
        statusView = TextView(this).apply {
            text = "MCP Android: " + TunnelState.status
            textSize = 14f
        }
        val connect = Button(this).apply {
            text = "Conectar ao MCP"
            setOnClickListener {
                val cameraAllowed = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val micAllowed = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!cameraAllowed || !micAllowed) {
                    statusView.text = "Conceda câmera e microfone para conectar"
                    requestPermissions()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("gateway_url", gateway.text.toString().trim())
                    .putString("pairing_code", pairing.text.toString().trim())
                    .apply()
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, AndroidTunnelService::class.java)
                        .setAction(AndroidTunnelService.ACTION_CONNECT)
                )
            }
        }
        val disconnect = Button(this).apply {
            text = "Desconectar"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, AndroidTunnelService::class.java)
                        .setAction(AndroidTunnelService.ACTION_DISCONNECT)
                )
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0x99000000.toInt())
            addView(statusView)
            addView(gateway, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pairing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(connect)
            addView(disconnect)
        }

        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        requestPermissions()
        root.post(object : Runnable {
            override fun run() {
                statusView.text = "MCP Android: " + TunnelState.status +
                    (TunnelState.lastError?.let { "\n" + it } ?: "")
                root.postDelayed(this, 500)
            }
        })
    }

    private fun requestPermissions() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCameraIfAllowed()
            maybeAutoConnect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun maybeAutoConnect() {
        if (autoConnectAttempted) return
        val cameraAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraAllowed || !micAllowed) return
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        val url = prefs.getString("gateway_url", "")?.trim().orEmpty()
        if (!url.startsWith("wss://") || CredentialStore(this).load().isNullOrBlank()) return
        autoConnectAttempted = true
        ContextCompat.startForegroundService(
            this,
            Intent(this, AndroidTunnelService::class.java)
                .setAction(AndroidTunnelService.ACTION_CONNECT)
        )
    }

    private fun startCameraIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                var bitmap: Bitmap? = null
                try {
                    bitmap = rgbaBitmap(image)
                    FrameStore.update(bitmap)
                } catch (_: Throwable) {
                } finally {
                    bitmap?.recycle()
                    image.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rgbaBitmap(image: ImageProxy): Bitmap {
        val bitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
