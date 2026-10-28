package com.demo.swarhands.tracking

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

/**
 * Reusable "front camera + hand tracking" bundle for games.
 * Call [start] once the CAMERA permission is granted and [release] in onDestroy.
 */
class HandCamera(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val numHands: Int,
    private val onResult: (result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: (usingGpu: Boolean) -> Unit
) {
    /** One thread for frames and tracker create/close, so they never race. */
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var tracker: HandTracker? = null

    fun start() {
        executor.execute {
            try {
                val t = HandTracker(activity, onResult, { msg -> activity.runOnUiThread { onError(msg) } }, numHands)
                tracker = t
                activity.runOnUiThread { onReady(t.usingGpu) }
            } catch (e: Exception) {
                val msg = if (e.message?.contains(HandTracker.MODEL_ASSET) == true) {
                    "Hand model not found. Build once with internet so Gradle can download it."
                } else {
                    "Could not start hand tracking: ${e.message}"
                }
                activity.runOnUiThread { onError(msg) }
            }
        }
        bindCamera()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val fps30 = Range(30, 30)

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

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

            analysis.setAnalyzer(executor) { proxy ->
                val t = tracker
                if (t == null) proxy.close() else t.detect(proxy, mirror = true)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onError("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun release() {
        executor.execute {
            tracker?.close()
            tracker = null
        }
        executor.shutdown()
    }
}
