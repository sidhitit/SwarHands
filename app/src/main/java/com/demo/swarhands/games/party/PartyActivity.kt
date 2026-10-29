package com.demo.swarhands.games.party

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
 * Haath Party: rapid-fire 5-second hand microgames. Opens on a mode picker:
 * Regular Fun (endless) or Boss Mode (boss fights). One hand is tracked.
 */
class PartyActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: PartyView
    private lateinit var statusText: TextView
    private lateinit var gameOverButtons: View
    private lateinit var picker: View
    private var mode: PartyMode? = null

    private lateinit var sfx: SfxPlayer
    private lateinit var camera: HandCamera
    private val tracker = PartyTracker()

    private val prefs by lazy { getSharedPreferences("party", Context.MODE_PRIVATE) }

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
        setContentView(R.layout.activity_party)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        gameOverButtons = findViewById(R.id.gameOverButtons)
        picker = findViewById(R.id.picker)

        sfx = SfxPlayer(this)
        gameView.sfx = sfx
        gameView.onLanguageChanged = { hinglish -> prefs.edit().putBoolean(KEY_HINGLISH, hinglish).apply() }
        gameView.onPhaseChanged = { phase ->
            gameOverButtons.removeCallbacks(showButtons)
            if (phase == PartyGame.Phase.GAME_OVER) {
                gameView.game?.let { g -> prefs.edit().putInt(bestKey(g.mode), g.best).apply() }
                gameOverButtons.postDelayed(showButtons, 1400)
            } else {
                gameOverButtons.animate().cancel()
                gameOverButtons.visibility = View.GONE
            }
        }

        findViewById<TextView>(R.id.bestRegular).text = bestLabel(PartyMode.REGULAR)
        findViewById<TextView>(R.id.bestBoss).text = bestLabel(PartyMode.BOSS)
        findViewById<View>(R.id.cardRegular).setOnClickListener { choose(PartyMode.REGULAR) }
        findViewById<View>(R.id.cardBoss).setOnClickListener { choose(PartyMode.BOSS) }
        findViewById<MaterialButton>(R.id.btnPickerBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnPlayAgain).setOnClickListener { gameView.restartGame() }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener {
            startActivity(Intent(this, PartyActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

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
            camera.start()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun choose(m: PartyMode) {
        if (mode != null) return
        mode = m
        gameView.start(m, prefs.getInt(bestKey(m), 0), prefs.getBoolean(KEY_HINGLISH, false))
        picker.animate().alpha(0f).setDuration(200).withEndAction { picker.visibility = View.GONE }.start()
    }

    private fun bestKey(m: PartyMode) = if (m == PartyMode.BOSS) KEY_BEST_BOSS else KEY_BEST

    private fun bestLabel(m: PartyMode): String {
        val best = prefs.getInt(bestKey(m), 0)
        return when {
            best <= 0 -> "New!"
            m == PartyMode.BOSS -> "Best: $best damage"
            else -> "Best: $best games"
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
        const val KEY_BEST = "best"
        const val KEY_BEST_BOSS = "best_boss"
        const val KEY_HINGLISH = "hinglish"
    }
}
