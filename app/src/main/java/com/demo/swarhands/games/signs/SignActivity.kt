package com.demo.swarhands.games.signs

import android.Manifest
import android.content.Context
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

/** Sign Says: watch a sequence of hand signs and shapes, then repeat it. Two hands are tracked. */
class SignActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: SignView
    private lateinit var statusText: TextView
    private lateinit var gameOverButtons: View

    private lateinit var sfx: SfxPlayer
    private lateinit var camera: HandCamera
    private val tracker = SignTracker()

    private val prefs by lazy { getSharedPreferences("signs", Context.MODE_PRIVATE) }

    private val showButtons = Runnable {
        gameOverButtons.alpha = 0f
        gameOverButtons.translationY = gameOverButtons.height * 0.5f
        gameOverButtons.visibility = View.VISIBLE
        gameOverButtons.animate().alpha(1f).translationY(0f).setDuration(300).start()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.start() else showStatus("Camera permission is needed to play.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signs)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        gameOverButtons = findViewById(R.id.gameOverButtons)

        sfx = SfxPlayer(this)
        gameView.sfx = sfx
        gameView.onPhaseChanged = { phase ->
            gameOverButtons.removeCallbacks(showButtons)
            if (phase == SignGame.Phase.GAME_OVER) {
                gameView.game?.let { g ->
                    prefs.edit().putInt(KEY_SCORE, g.bestScore).putInt(KEY_ROUND, g.bestRound).apply()
                }
                gameOverButtons.postDelayed(showButtons, 1400)
            } else {
                gameOverButtons.animate().cancel()
                gameOverButtons.visibility = View.GONE
            }
        }
        gameView.start(prefs.getInt(KEY_SCORE, 0), prefs.getInt(KEY_ROUND, 0))

        findViewById<MaterialButton>(R.id.btnPlayAgain).setOnClickListener { gameView.restartGame() }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        showStatus("Loading hand tracking…")
        camera = HandCamera(
            activity = this,
            previewView = previewView,
            numHands = 2, // two-hand numbers and the heart
            onResult = ::onHandResult,
            onError = { msg -> showStatus(msg) },
            onReady = { statusText.visibility = View.GONE }
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.start()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Runs on MediaPipe's result thread. */
    private fun onHandResult(result: HandLandmarkerResult, imgW: Int, imgH: Int) {
        val transform = gameView.transformFor(imgW, imgH) ?: return
        gameView.submit(tracker.process(result, transform))
    }

    private fun showStatus(text: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        gameOverButtons.removeCallbacks(showButtons)
        camera.release()
        sfx.release()
    }

    private companion object {
        const val KEY_SCORE = "best_score"
        const val KEY_ROUND = "best_round"
    }
}
