package com.demo.swarhands.games.rps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.demo.swarhands.R
import com.demo.swarhands.audio.SfxPlayer
import com.demo.swarhands.tracking.HandCamera
import com.google.android.material.button.MaterialButton
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class RpsActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: RpsGameView
    private lateinit var statusText: TextView
    private lateinit var gameOverButtons: View

    private lateinit var sfx: SfxPlayer
    private var camera: HandCamera? = null
    private val smoother = RpsSmoother() // only touched on the tracking thread

    private val showButtons = Runnable {
        gameOverButtons.alpha = 0f
        gameOverButtons.translationY = gameOverButtons.height * 0.5f
        gameOverButtons.visibility = View.VISIBLE
        gameOverButtons.animate().alpha(1f).translationY(0f).setDuration(300).start()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera?.start() else showStatus("Camera permission is needed to play.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rps)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        gameOverButtons = findViewById(R.id.gameOverButtons)

        sfx = SfxPlayer(this)
        gameView.sfx = sfx
        gameView.onPhaseChanged = { phase ->
            gameOverButtons.removeCallbacks(showButtons)
            if (phase == RpsGame.Phase.GAME_OVER) {
                gameOverButtons.postDelayed(showButtons, 1200)
            } else {
                gameOverButtons.animate().cancel()
                gameOverButtons.visibility = View.GONE
            }
        }
        findViewById<MaterialButton>(R.id.btnPlayAgain).setOnClickListener { gameView.restartGame() }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        showStatus("Loading hand tracking…")
        camera = HandCamera(
            activity = this,
            previewView = previewView,
            numHands = 1, // one hand is all RPS needs -> about half the tracking cost
            onResult = ::onHandResult,
            onError = { msg -> showStatus(msg) },
            onReady = { statusText.visibility = View.GONE }
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera?.start()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Runs on MediaPipe's result thread. */
    private fun onHandResult(result: HandLandmarkerResult, imgW: Int, imgH: Int) {
        val lm = result.landmarks().firstOrNull()
        if (lm == null || lm.size < 21) {
            gameView.submitHand(smoother.push(null), null)
            return
        }
        val move = smoother.push(RpsClassifier.classify(lm, imgW, imgH))
        val transform = gameView.transformFor(imgW, imgH)
        val points = transform?.let { t -> lm.map { t.map(it.x(), it.y()) } }
        gameView.submitHand(move, points)
    }

    private fun showStatus(text: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        gameOverButtons.removeCallbacks(showButtons)
        camera?.release()
        sfx.release()
    }
}
