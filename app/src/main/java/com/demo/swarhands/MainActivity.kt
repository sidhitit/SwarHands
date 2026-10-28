package com.demo.swarhands

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.demo.swarhands.audio.Instrument
import com.demo.swarhands.audio.SoundEngine
import com.demo.swarhands.gesture.GestureEngine
import com.demo.swarhands.gesture.PlayMode
import com.demo.swarhands.tracking.HandTracker
import com.demo.swarhands.ui.OverlayView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var hintText: TextView
    private lateinit var btnInstrument: MaterialButton
    private lateinit var btnPitch: MaterialButton

    /** Single thread for camera frames AND tracker create/close, so they never race. */
    private lateinit var analysisExecutor: ExecutorService
    @Volatile private var handTracker: HandTracker? = null

    private lateinit var soundEngine: SoundEngine
    private lateinit var gestureEngine: GestureEngine

    private var instrument = Instrument.PIANO
    private var saIndex = 0 // 0 = C ... 11 = B
    private var trackerReady = false
    private var soundsReady = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showStatus("Camera permission is needed to track your hands.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        hintText = findViewById(R.id.hintText)
        btnInstrument = findViewById(R.id.btnInstrument)
        btnPitch = findViewById(R.id.btnPitch)

        analysisExecutor = Executors.newSingleThreadExecutor()
        soundEngine = SoundEngine(this)
        gestureEngine = GestureEngine(soundEngine)

        setupControls()
        updateHint()
        reloadSounds()

        showStatus("Loading hand model…")
        analysisExecutor.execute { createTracker() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupControls() {
        findViewById<MaterialButtonToggleGroup>(R.id.modeToggle)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val mode = if (checkedId == R.id.btnAir) PlayMode.AIR_KEYS else PlayMode.PINCH
                gestureEngine.mode = mode
                overlay.mode = mode
                updateHint()
            }

        btnInstrument.setOnClickListener {
            instrument = Instrument.entries[(instrument.ordinal + 1) % Instrument.entries.size]
            reloadSounds()
        }
        btnPitch.setOnClickListener {
            saIndex = (saIndex + 1) % PITCH_NAMES.size
            reloadSounds()
        }
    }

    private fun updateHint() {
        hintText.setText(if (gestureEngine.mode == PlayMode.PINCH) R.string.hint_pinch else R.string.hint_air)
    }

    private fun reloadSounds() {
        btnInstrument.text = "🎵 ${instrument.label}"
        btnPitch.text = "Sa = ${PITCH_NAMES[saIndex]}"
        soundsReady = false
        refreshStatus()
        val saHz = SA_C4_HZ * 2.0.pow(saIndex / 12.0)
        soundEngine.load(instrument, saHz) {
            runOnUiThread {
                soundsReady = true
                refreshStatus()
            }
        }
    }

    private fun createTracker() {
        try {
            handTracker = HandTracker(
                context = this,
                onResult = ::onHandResult,
                onError = { msg -> runOnUiThread { showStatus("Tracking error: $msg") } }
            )
            runOnUiThread {
                trackerReady = true
                refreshStatus()
            }
        } catch (e: Exception) {
            val msg = if (e.message?.contains(HandTracker.MODEL_ASSET) == true) {
                "Hand model not found. Build once with internet so Gradle can download it."
            } else {
                "Could not start hand tracking: ${e.message}"
            }
            runOnUiThread { showStatus(msg) }
        }
    }

    // --- performance stats (tracking thread) ---
    private var statFrames = 0
    private var statLatencySum = 0L
    private var statWindowStart = 0L

    /** Runs on MediaPipe's result thread. */
    private fun onHandResult(result: HandLandmarkerResult, imgW: Int, imgH: Int) {
        val transform = overlay.transformFor(imgW, imgH) ?: return
        val frame = gestureEngine.process(result, imgW, imgH, transform)
        overlay.submit(frame)
        recordStats(SystemClock.uptimeMillis() - result.timestampMs())
    }

    /** Shows "GPU · 30 fps · 35 ms" so you can see where the slowness is. */
    private fun recordStats(latencyMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (statWindowStart == 0L) statWindowStart = now
        statFrames++
        statLatencySum += latencyMs
        val elapsed = now - statWindowStart
        if (elapsed >= 1000) {
            val fps = statFrames * 1000 / elapsed
            val avgLatency = statLatencySum / statFrames
            val delegate = if (handTracker?.usingGpu == true) "GPU" else "CPU"
            val text = "$delegate · $fps fps · $avgLatency ms"
            runOnUiThread { statsText.text = text }
            statFrames = 0
            statLatencySum = 0
            statWindowStart = now
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // Same 4:3 aspect for preview and analysis so the skeleton lines up with the video.
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            // Ask for a steady 30 fps. Many front cameras silently drop to 15 fps indoors otherwise.
            val fps30 = Range(30, 30)

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30)
            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30)
            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val tracker = handTracker
                if (tracker == null) proxy.close() else tracker.detect(proxy, mirror = true)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                showStatus("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshStatus() {
        when {
            !trackerReady -> showStatus("Loading hand model…")
            !soundsReady -> showStatus("Tuning ${instrument.label} to Sa = ${PITCH_NAMES[saIndex]}…")
            else -> {
                showStatus("Ready! Show your hands 👋")
                statusText.removeCallbacks(hideStatus)
                statusText.postDelayed(hideStatus, 2500)
            }
        }
    }

    private val hideStatus = Runnable { statusText.visibility = View.GONE }

    private fun showStatus(text: String) {
        statusText.removeCallbacks(hideStatus)
        statusText.visibility = View.VISIBLE
        statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        statusText.removeCallbacks(hideStatus)
        analysisExecutor.execute {
            handTracker?.close()
            handTracker = null
        }
        analysisExecutor.shutdown()
        soundEngine.release()
    }

    private companion object {
        const val SA_C4_HZ = 261.6256
        val PITCH_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
