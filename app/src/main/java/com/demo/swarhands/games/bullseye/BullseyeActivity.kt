package com.demo.swarhands.games.bullseye

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
 * Bullseye: shoot a target with a bow (two hands) or a slingshot (one hand).
 * Opens on a weapon picker; the camera starts only after a weapon is chosen, tracking
 * just the number of hands that weapon needs.
 */
class BullseyeActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: BullseyeView
    private lateinit var statusText: TextView
    private lateinit var picker: View
    private lateinit var gameOverButtons: View

    private lateinit var sfx: SfxPlayer
    private var camera: HandCamera? = null
    @Volatile private var tracker: AimTracker? = null
    private var weapon: Weapon? = null

    private val prefs by lazy { getSharedPreferences("bullseye", Context.MODE_PRIVATE) }

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
        setContentView(R.layout.activity_bullseye)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        picker = findViewById(R.id.picker)
        gameOverButtons = findViewById(R.id.gameOverButtons)

        sfx = SfxPlayer(this)
        gameView.sfx = sfx
        gameView.onPhaseChanged = { phase ->
            gameOverButtons.removeCallbacks(showButtons)
            if (phase == BullseyeGame.Phase.GAME_OVER) {
                gameView.game?.let { g -> if (g.newBest) prefs.edit().putInt(bestKey(g.weapon), g.best).apply() }
                gameOverButtons.postDelayed(showButtons, 1600)
            } else {
                gameOverButtons.animate().cancel()
                gameOverButtons.visibility = View.GONE
            }
        }

        findViewById<WeaponIconView>(R.id.iconBow).weapon = Weapon.BOW
        findViewById<WeaponIconView>(R.id.iconSling).weapon = Weapon.SLINGSHOT
        findViewById<TextView>(R.id.bestBow).text = bestLabel(Weapon.BOW)
        findViewById<TextView>(R.id.bestSling).text = bestLabel(Weapon.SLINGSHOT)
        findViewById<View>(R.id.cardBow).setOnClickListener { choose(Weapon.BOW) }
        findViewById<View>(R.id.cardSling).setOnClickListener { choose(Weapon.SLINGSHOT) }
        findViewById<MaterialButton>(R.id.btnPickerBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnPlayAgain).setOnClickListener { gameView.restartGame() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener {
            // camera is set up for one hand count; a fresh screen is the simplest clean switch
            startActivity(Intent(this, BullseyeActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun choose(w: Weapon) {
        if (weapon != null) return
        weapon = w
        tracker = AimTracker(w)
        gameView.start(w, prefs.getInt(bestKey(w), 0))
        picker.animate().alpha(0f).setDuration(200).withEndAction { picker.visibility = View.GONE }.start()

        showStatus("Loading hand tracking…")
        camera = HandCamera(
            activity = this,
            previewView = previewView,
            numHands = w.hands, // slingshot tracks one hand -> about half the work
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
        gameView.submitAim(t.process(result, transform, gameView.anchorX, gameView.anchorY))
    }

    private fun bestKey(w: Weapon) = "best_${w.name}"

    private fun bestLabel(w: Weapon): String {
        val best = prefs.getInt(bestKey(w), 0)
        return if (best > 0) "Best: $best" else "New!"
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
