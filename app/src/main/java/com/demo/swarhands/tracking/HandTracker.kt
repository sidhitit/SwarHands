package com.demo.swarhands.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max

/**
 * Wraps MediaPipe HandLandmarker (free, on-device). 21 landmarks per hand, up to 2 hands.
 * Create, detect and close must all be called from the same single-thread executor.
 *
 * Speed-ups vs the first version:
 *  - GPU delegate with automatic CPU fallback
 *  - lower presence/tracking thresholds so MediaPipe keeps *tracking* instead of
 *    re-running the expensive palm detector every time confidence dips
 *  - reused input bitmap + direct buffer copy instead of ImageProxy.toBitmap()
 *  - rotate/mirror drawn in one pass, no bilinear filtering
 */
class HandTracker(
    context: Context,
    private val onResult: (result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) -> Unit,
    private val onError: (String) -> Unit,
    private val numHands: Int = 2 // 1 hand = roughly half the work, use it when a game needs only one
) {
    private val landmarker: HandLandmarker
    var usingGpu = false
        private set

    private var lastTimestamp = 0L
    private var closed = false
    private var rawBitmap: Bitmap? = null
    private val matrix = Matrix()

    init {
        landmarker = try {
            build(context, Delegate.GPU).also { usingGpu = true }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
            build(context, Delegate.CPU)
        }
    }

    private fun build(context: Context, delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(numHands)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setResultListener { result: HandLandmarkerResult, input: MPImage ->
                onResult(result, input.width, input.height)
            }
            .setErrorListener { e: RuntimeException -> onError(e.message ?: "Hand tracking error") }
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    /**
     * Rotates the frame upright and mirrors it (front camera) before detection.
     * Mirroring makes MediaPipe's "Left"/"Right" match the user's real hands and
     * makes coordinates match the mirrored PreviewView.
     */
    fun detect(imageProxy: ImageProxy, mirror: Boolean) {
        if (closed) {
            imageProxy.close()
            return
        }
        val rotation: Int
        val raw: Bitmap
        try {
            rotation = imageProxy.imageInfo.rotationDegrees
            raw = copyToRawBitmap(imageProxy)
        } finally {
            imageProxy.close()
        }

        val w = raw.width
        val h = raw.height
        val outW = if (rotation % 180 == 0) w else h
        val outH = if (rotation % 180 == 0) h else w

        // New output bitmap per frame: MediaPipe processes it asynchronously, so it must not be reused.
        val upright = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        matrix.reset()
        matrix.postTranslate(-w / 2f, -h / 2f)
        matrix.postRotate(rotation.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        matrix.postTranslate(outW / 2f, outH / 2f)
        Canvas(upright).drawBitmap(raw, matrix, null) // null paint = no filtering, fastest

        // LIVE_STREAM requires strictly increasing timestamps
        val ts = max(SystemClock.uptimeMillis(), lastTimestamp + 1)
        lastTimestamp = ts
        landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
    }

    /** Copies the RGBA plane into a reused bitmap (fast path), or falls back to toBitmap(). */
    private fun copyToRawBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val w = proxy.width
        val h = proxy.height
        if (plane.pixelStride != 4 || plane.rowStride != w * 4) {
            return proxy.toBitmap() // row padding on this device: use the safe path
        }
        val bmp = rawBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rawBitmap = it }
        val buffer = plane.buffer
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    fun close() {
        if (closed) return
        closed = true
        landmarker.close()
    }

    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        private const val TAG = "HandTracker"
    }
}
