package com.demo.swarhands.games.cricket

import android.Manifest
import android.content.Context
import android.content.Intent
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

/**
 * Gully Cricket: bat with your hand. Opens on a mode picker (Chase levels or an endless
 * Super Over); the camera starts only after a mode is chosen and tracks a single hand.
 */
class CricketActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: CricketView
    private lateinit var statusText: TextView
    private lateinit var picker: View
    private lateinit var gameOverButtons: View
    private lateinit var btnPlayAgain: MaterialButton

    private lateinit var sfx: SfxPlayer
    private var camera: HandCamera? = null
    @Volatile private var tracker: BatTracker? = null
    private var mode: CricketMode? = null

    private val prefs by lazy { getSharedPreferences("cricket", Context.MODE_PRIVATE) }

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
        setContentView(R.layout.activity_cricket)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        picker = findViewById(R.id.picker)
        gameOverButtons = findViewById(R.id.gameOverButtons)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)

        sfx = SfxPlayer(this)
        gameView.sfx = sfx
        gameView.onPhaseChanged = { phase ->
            gameOverButtons.removeCallbacks(showButtons)
            if (phase == CricketGame.Phase.GAME_OVER) {
                gameView.game?.let { g ->
                    if (g.newBest) prefs.edit().putInt(bestKey(g.mode), g.best).apply()
                    btnPlayAgain.setText(
                        if (g.mode == CricketMode.CHASE && !g.won) R.string.try_again else R.string.play_again
                    )
                }
                gameOverButtons.postDelayed(showButtons, 1600)
            } else {
                if (phase == CricketGame.Phase.LEVEL_CLEAR) {
                    gameView.game?.let { g -> prefs.edit().putInt(bestKey(g.mode), g.best).apply() }
                }
                gameOverButtons.animate().cancel()
                gameOverButtons.visibility = View.GONE
            }
        }

        findViewById<TextView>(R.id.bestChase).text = bestLabel(CricketMode.CHASE)
        findViewById<TextView>(R.id.bestSuper).text = bestLabel(CricketMode.SUPER_OVER)
        findViewById<View>(R.id.cardChase).setOnClickListener { choose(CricketMode.CHASE) }
        findViewById<View>(R.id.cardSuper).setOnClickListener { choose(CricketMode.SUPER_OVER) }
        findViewById<MaterialButton>(R.id.btnPickerBack).setOnClickListener { finish() }

        btnPlayAgain.setOnClickListener { gameView.restartGame() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener {
            startActivity(Intent(this, CricketActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun choose(m: CricketMode) {
        if (mode != null) return
        mode = m
        tracker = BatTracker()
        // chase starts at the level after the best one cleared
        val best = prefs.getInt(bestKey(m), 0)
        val level = if (m == CricketMode.CHASE) (best + 1).coerceIn(1, CricketGame.LEVELS.size) else 1
        gameView.start(m, level, best)
        picker.animate().alpha(0f).setDuration(200).withEndAction { picker.visibility = View.GONE }.start()

        showStatus("Loading hand tracking…")
        camera = HandCamera(
            activity = this,
            previewView = previewView,
            numHands = 1,
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
        val t = tracker ?: return
        val transform = gameView.transformFor(imgW, imgH) ?: return
        gameView.submitBat(t.process(result, transform))
    }

    private fun bestKey(m: CricketMode) = "best_${m.name}"

    private fun bestLabel(m: CricketMode): String {
        val best = prefs.getInt(bestKey(m), 0)
        return when {
            best <= 0 -> "New!"
            m == CricketMode.CHASE -> "Cleared: level $best/${CricketGame.LEVELS.size}"
            else -> "Best: $best runs"
        }
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
