package com.demo.swarhands.games.party

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.demo.swarhands.audio.Sfx
import com.demo.swarhands.audio.SfxPlayer
import com.demo.swarhands.games.party.Kit.Companion.BLUE
import com.demo.swarhands.games.party.Kit.Companion.GOLD
import com.demo.swarhands.games.party.Kit.Companion.GREEN
import com.demo.swarhands.games.party.Kit.Companion.MUTED
import com.demo.swarhands.games.party.Kit.Companion.PARTY
import com.demo.swarhands.games.party.Kit.Companion.RED
import com.demo.swarhands.games.party.Kit.Companion.SOFT_WHITE
import com.demo.swarhands.games.party.PartyGame.Phase
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.ViewTransform
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws Haath Party over the camera preview: HUD, the instruction splash, the current
 * microgame, the fuse, results and game over. The microgames draw themselves with [kit].
 */
class PartyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), PartyGame.Listener {

    var sfx: SfxPlayer? = null
    var onPhaseChanged: ((Phase) -> Unit)? = null
    var onLanguageChanged: ((Boolean) -> Unit)? = null
    var game: PartyGame? = null
        private set

    @Volatile private var frame: HandFrame? = null

    private val kit = Kit(resources.displayMetrics.density,
        resources.displayMetrics.density * resources.configuration.fontScale.coerceAtMost(1.3f))
    private fun dp(v: Float) = kit.dp(v)
    private fun sp(v: Float) = kit.sp(v)

    private val topScrim = Paint()
    private val bottomScrim = Paint()
    private val heartPath = Path()
    private val langRect = RectF()
    private val barTop get() = dp(14f)
    private val barBottom get() = dp(90f)

    private val games = listOf(
        FistGame(kit), HighFiveGame(kit), CountGame(kit), ThumbsGame(kit), BeeGame(kit),
        EggGame(kit), DodgeGame(kit), PopGame(kit), CopyGame(kit), FreezeGame(kit)
    )
    private val tricks = listOf<Microgame>(DontGame(kit))
    private val bosses: List<Boss> by lazy {
        val swarm = SwarmGame(kit)
        val bat = BatGame(kit)
        val memory = MemoryGame(kit)
        val kite = KiteGame(kit)
        listOf(
            Boss(Words("MAKKHI RAJA", "MAKKHI RAJA"), "🐝", 60, listOf(swarm)),
            Boss(Words("GULLY BOWLER", "GULLY BOWLER"), "🦹", 80, listOf(bat)),
            Boss(Words("SIGN SORCERER", "JAADUGAR"), "🧙", 100, listOf(memory)),
            Boss(Words("KITE DEMON", "PATANG RAKSHAS"), "👹", 120, listOf(kite)),
            Boss(Words("MEGA DRAGON", "MAHA DRAGON"), "🐉", 200, listOf(swarm, bat, memory, kite))
        )
    }

    // ---------- cached strings ----------
    private var scoreStr = "0"
    private var scoreFor = -1
    private var speedStr = "x1.0"
    private var speedFor = -1
    private var endLine1 = ""
    private var endLine2 = ""
    private var endLine3 = ""
    private var lastFrameAt = 0L
    private var hpShown = 1f
    private var damageStr = ""
    private var comboStr = ""
    private var comboFor = -1
    private var bossLabel = ""
    private var bossLabelFor = -1

    // =====================================================================
    // Public API
    // =====================================================================

    fun start(mode: PartyMode, best: Int, hinglish: Boolean) {
        kit.hinglish = hinglish
        game = PartyGame(mode, games, tricks, bosses, best, this).also { it.mirrorWidth = width.toFloat() }
        invalidate()
    }

    /** Thread-safe: called from the tracking thread. */
    fun transformFor(imgW: Int, imgH: Int): ViewTransform? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        return ViewTransform(w, h, imgW, imgH)
    }

    /** Thread-safe: called from the tracking thread. */
    fun submit(f: HandFrame) {
        frame = f
        postInvalidate()
    }

    fun restartGame() {
        kit.clearParticles()
        game?.restart(SystemClock.uptimeMillis())
        invalidate()
    }

    // =====================================================================
    // PartyGame.Listener (UI thread, from game.update)
    // =====================================================================

    override fun onSfx(sfx: Sfx, volume: Float, rate: Float) {
        this.sfx?.play(sfx, volume, rate)
    }

    override fun onHaptic(strong: Boolean) {
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onPhaseChanged(phase: Phase) {
        val g = game
        when (phase) {
            Phase.GAME_OVER -> {
                updateEndLines()
                if (g?.newBest == true) kit.burst(width / 2f, height * 0.3f, 60, PARTY)
            }
            Phase.RESULT -> if (g != null && g.mode == PartyMode.BOSS && g.lastWon) damageStr = "-${g.lastDamage}"
            Phase.BOSS_INTRO -> hpShown = 1f
            Phase.BOSS_DOWN -> kit.burst(width / 2f, bossY(), 50, PARTY)
            else -> Unit
        }
        post { onPhaseChanged?.invoke(phase) } // never touch other views mid-draw
    }

    private fun updateEndLines() {
        val g = game ?: return
        val hi = kit.hinglish
        endLine1 = if (g.mode == PartyMode.BOSS) {
            if (hi) "${g.bossesBeaten} boss haraye · ${g.score} damage" else "${g.bossesBeaten} bosses beaten · ${g.score} damage"
        } else {
            if (hi) "${g.score} games jeete" else "${g.score} games won"
        }
        endLine2 = if (g.newBest) (if (hi) "NAYA RECORD!" else "NEW BEST!") else (if (hi) "Best: ${g.best}" else "Best: ${g.best}")
        endLine3 = g.lostTo?.let { (if (hi) "Haraya: " else "Beaten by: ") + it.title.get(hi) } ?: ""
    }

    // =====================================================================
    // Touch: the language chip
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return langRect.contains(e.x, e.y)
        if (e.action == MotionEvent.ACTION_UP && langRect.contains(e.x, e.y)) {
            kit.hinglish = !kit.hinglish
            if (game?.phase == Phase.GAME_OVER) updateEndLines()
            onLanguageChanged?.invoke(kit.hinglish)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
            return true
        }
        return false
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        kit.w = w.toFloat()
        kit.h = h.toFloat()
        game?.mirrorWidth = w.toFloat()
        topScrim.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bottomScrim.shader = LinearGradient(0f, h * 0.7f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val g = game ?: return
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameAt == 0L) 16f else (now - lastFrameAt).coerceIn(1, 50).toFloat()
        lastFrameAt = now
        val f = frame
        val w = width.toFloat()
        val h = height.toFloat()

        g.update(now, dtMs, f)
        val phase = g.phase
        val t = now - g.phaseStart

        c.drawRect(0f, 0f, w, h * 0.3f, topScrim)
        c.drawRect(0f, h * 0.7f, w, h, bottomScrim)
        drawHands(c, f)

        val boss = g.mode == PartyMode.BOSS
        val inGame = phase == Phase.INSTRUCT || phase == Phase.PLAY || phase == Phase.RESULT
        if (inGame) g.current.draw(c, now, phase != Phase.INSTRUCT, g.input(f))
        if (kit.particlesActive) kit.drawParticles(c, dtMs / 1000f)
        drawHud(c, g, w, now)
        if (boss && phase != Phase.GAME_OVER && phase != Phase.BOSS_INTRO) drawBossPanel(c, g, w, now, dtMs)

        when (phase) {
            Phase.INTRO -> drawIntro(c, t, w, h)
            Phase.BOSS_INTRO -> drawBossIntro(c, g, t, w, h)
            Phase.INSTRUCT -> drawInstruction(c, g, t, w, h)
            Phase.PLAY -> {
                val y = if (boss) bossY() + dp(62f) else barBottom + dp(46f)
                val cur = g.current
                kit.text(c, cur.title.get(kit.hinglish), w / 2f, y, sp(if (cur.bossAttack) 22f else 26f), if (cur.fakeOut) RED else GOLD)
                if (g.mirror) kit.text(c, if (kit.hinglish) "🪞 ULTA!" else "🪞 MIRROR!", w / 2f, y + dp(30f), sp(16f), BLUE)
                drawFuse(c, g.fuse(now), w, h, now)
            }
            Phase.RESULT -> drawResult(c, g, t, w, h)
            Phase.SPEED_UP -> drawSpeedUp(c, g, t, w, h)
            Phase.BOSS_DOWN -> drawBossDown(c, g, t, w, h)
            Phase.GAME_OVER -> drawGameOver(c, t, w, h)
        }
        // the boss hits back: a red flash
        val sinceHit = now - g.playerHitAt
        if (boss && g.playerHitAt > 0 && sinceHit < 350) {
            kit.fill.color = RED
            kit.fill.alpha = (110 * (1f - sinceHit / 350f)).toInt()
            c.drawRect(0f, 0f, w, h, kit.fill)
        }
        if ((phase == Phase.INSTRUCT || phase == Phase.PLAY) && f?.visible != true) {
            kit.text(c, if (kit.hinglish) "Haath dikhao ✋" else "Show your hand ✋", w / 2f, h - dp(70f), sp(17f), SOFT_WHITE)
        }
        if (phase != Phase.GAME_OVER || t < 3000 || kit.particlesActive) postInvalidateOnAnimation()
    }

    private fun drawHands(c: Canvas, f: HandFrame?) {
        val hands = f?.hands ?: return
        kit.stroke.strokeWidth = dp(2.5f)
        kit.stroke.color = Color.argb(110, 255, 255, 255)
        for (pts in hands) {
            for ((i, j) in HandSkeleton.BONES) c.drawLine(pts[i].x, pts[i].y, pts[j].x, pts[j].y, kit.stroke)
        }
    }

    // ---------- HUD ----------

    private fun drawHud(c: Canvas, g: PartyGame, w: Float, now: Long) {
        val top = barTop
        kit.rect.set(dp(16f), top, w - dp(16f), barBottom)
        kit.fill.color = Color.argb(205, 14, 14, 24)
        c.drawRoundRect(kit.rect, dp(22f), dp(22f), kit.fill)
        kit.stroke.strokeWidth = dp(1f)
        kit.stroke.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(kit.rect, dp(22f), dp(22f), kit.stroke)

        if (scoreFor != g.score) {
            scoreFor = g.score
            scoreStr = g.score.toString()
        }
        if (speedFor != g.level) {
            speedFor = g.level
            speedStr = "x" + String.format("%.1f", g.speed)
        }
        val hi = kit.hinglish
        val boss = g.mode == PartyMode.BOSS
        kit.text(c, if (boss) "DAMAGE" else if (hi) "JEETE" else "SCORE", w * 0.17f, top + dp(22f), sp(11f), MUTED, outline = false)
        kit.text(c, scoreStr, w * 0.17f, top + dp(50f), sp(26f), GOLD, outline = false)
        if (boss) {
            val key = g.bossIndex + g.lap * 100
            if (key != bossLabelFor) {
                bossLabelFor = key
                bossLabel = "${g.bossIndex + 1}/5" + if (g.lap > 0) " ★${g.lap}" else ""
            }
            kit.text(c, "BOSS", w * 0.83f, top + dp(22f), sp(11f), MUTED, outline = false)
            kit.text(c, bossLabel, w * 0.83f, top + dp(50f), sp(22f), Color.WHITE, outline = false)
        } else {
            kit.text(c, if (hi) "RAFTAAR" else "SPEED", w * 0.83f, top + dp(22f), sp(11f), MUTED, outline = false)
            kit.text(c, speedStr, w * 0.83f, top + dp(50f), sp(24f), Color.WHITE, outline = false)
        }
        kit.text(c, if (boss) "BOSS MODE" else "HAATH PARTY", w / 2f, top + dp(22f), sp(12f), if (boss) RED else Color.WHITE, outline = false)
        val n = g.maxLives
        for (i in 0 until n) {
            val x = w / 2f + (i - (n - 1) / 2f) * dp(24f)
            val on = i < g.lives
            val lostNow = g.phase == Phase.RESULT && !g.lastWon && i == g.lives && now - g.phaseStart < 600
            val k = if (lostNow) 1f + 0.4f * sin((now - g.phaseStart) / 600f * PI.toFloat()) else 1f
            heart(c, x, top + dp(50f), dp(9f) * k, if (on || lostNow) RED else Color.argb(70, 255, 255, 255))
        }

        // language chip under the bar
        val cy = barBottom + dp(16f)
        langRect.set(w - dp(96f), cy - dp(13f), w - dp(16f), cy + dp(13f))
        kit.fill.color = Color.argb(170, 0, 0, 0)
        c.drawRoundRect(langRect, dp(13f), dp(13f), kit.fill)
        kit.stroke.color = Color.argb(90, 255, 255, 255)
        c.drawRoundRect(langRect, dp(13f), dp(13f), kit.stroke)
        val mid = langRect.centerX()
        kit.text(c, "EN", mid - dp(20f), cy, sp(12f), if (!hi) GOLD else MUTED, outline = false)
        kit.text(c, "|", mid, cy, sp(12f), MUTED, outline = false)
        kit.text(c, "HI", mid + dp(20f), cy, sp(12f), if (hi) GOLD else MUTED, outline = false)
        langRect.inset(-dp(8f), -dp(8f)) // easier to tap
    }

    private fun heart(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        kit.fill.color = color
        c.drawCircle(x - r * 0.5f, y - r * 0.2f, r * 0.55f, kit.fill)
        c.drawCircle(x + r * 0.5f, y - r * 0.2f, r * 0.55f, kit.fill)
        heartPath.reset()
        heartPath.moveTo(x - r * 1.02f, y - r * 0.05f)
        heartPath.lineTo(x, y + r * 0.95f)
        heartPath.lineTo(x + r * 1.02f, y - r * 0.05f)
        heartPath.close()
        c.drawPath(heartPath, kit.fill)
    }

    // ---------- phases ----------

    private fun drawIntro(c: Canvas, t: Long, w: Float, h: Float) {
        val cy = h * 0.45f
        val p = (t / 450f).coerceAtMost(1f)
        val fade = if (t > PartyGame.INTRO_MS - 300) (PartyGame.INTRO_MS - t) / 300f else 1f
        val a = (255 * fade.coerceIn(0f, 1f)).toInt()
        kit.fill.color = Color.BLACK
        kit.fill.alpha = a * 140 / 255
        c.drawRect(0f, cy - dp(90f), w, cy + dp(90f), kit.fill)
        kit.text(c, "HAATH PARTY! 🎉", w / 2f, cy - dp(40f), sp(40f), GOLD, a, 0.6f + 0.4f * easeOutBack(p))
        val p2 = ((t - 250) / 400f).coerceIn(0f, 1f)
        val hi = kit.hinglish
        kit.text(c, if (hi) "Jo bole, woh karo…" else "Do what it says…", w / 2f, cy + dp(12f), sp(20f), Color.WHITE, (a * p2).toInt())
        kit.text(c, if (hi) "JALDI!" else "FAST!", w / 2f, cy + dp(48f), sp(26f), RED, (a * p2).toInt())
    }

    private fun drawInstruction(c: Canvas, g: PartyGame, t: Long, w: Float, h: Float) {
        val p = (t / 260f).coerceAtMost(1f)
        val out = ((g.instructMs - t) / 150f).coerceIn(0f, 1f)
        val cy = h * (if (g.mode == PartyMode.BOSS) 0.37f else 0.3f)
        kit.fill.color = Color.BLACK
        kit.fill.alpha = (150 * out).toInt()
        c.drawRect(0f, cy - dp(52f), w, cy + dp(52f), kit.fill)
        val cur = g.current
        if (cur.bossAttack) {
            kit.text(c, if (kit.hinglish) "⚠ BOSS KA VAAR!" else "⚠ BOSS ATTACK!", w / 2f, cy - dp(70f), sp(26f), RED, (255 * out).toInt())
        }
        kit.text(c, cur.title.get(kit.hinglish), w / 2f, cy, sp(if (cur.bossAttack) 40f else 54f), if (cur.fakeOut) RED else GOLD,
            (255 * out).toInt(), 0.4f + 0.6f * easeOutBack(p))
        if (g.mirror) kit.text(c, if (kit.hinglish) "🪞 ULTA! Left-right badal gaye" else "🪞 MIRROR! Left and right are swapped", w / 2f, cy + dp(46f), sp(16f), BLUE, (255 * out).toInt())
    }

    private fun drawFuse(c: Canvas, left: Float, w: Float, h: Float, now: Long) {
        val y = h - dp(30f)
        val x0 = dp(24f)
        val x1 = w - dp(24f)
        kit.stroke.strokeWidth = dp(8f)
        kit.stroke.color = Color.argb(80, 255, 255, 255)
        c.drawLine(x0, y, x1, y, kit.stroke)
        val end = x0 + (x1 - x0) * left
        kit.stroke.color = if (left > 0.5f) GREEN else if (left > 0.25f) GOLD else RED
        c.drawLine(x0, y, end, y, kit.stroke)
        kit.emoji(c, "🔥", end, y - dp(4f), dp(30f) * (1f + 0.15f * sin(now / 50f)))
    }

    private fun drawResult(c: Canvas, g: PartyGame, t: Long, w: Float, h: Float) {
        val p = (t / 300f).coerceAtMost(1f)
        val cy = h * (if (g.mode == PartyMode.BOSS) 0.37f else 0.3f)
        val hi = kit.hinglish
        val won = g.lastWon
        kit.emoji(c, if (won) "✅" else "❌", w / 2f, cy - dp(20f), dp(70f) * easeOutBack(p))
        val word = if (won) (if (hi) "SHABAASH!" else "NICE!") else (if (hi) "ARRE!" else "MISS!")
        kit.text(c, word, w / 2f, cy + dp(46f), sp(36f), if (won) GREEN else RED, 255, 0.5f + 0.5f * easeOutBack(p))
        val note = g.current.note
        if (note != null && !won) kit.text(c, note.get(hi), w / 2f, cy + dp(84f), sp(17f), Color.WHITE)
        if (g.mode == PartyMode.BOSS && won) {
            // damage number flies up from the boss
            val q = (t / 700f).coerceAtMost(1f)
            kit.text(c, damageStr, w * 0.3f, bossY() - dp(10f) - q * dp(30f), sp(30f), RED, (255 * (1f - q * 0.5f)).toInt())
        }
    }

    // ---------- Boss Mode ----------

    private fun bossY() = barBottom + dp(56f)

    /** Boss face, name, health bar and your combo, under the score bar. */
    private fun drawBossPanel(c: Canvas, g: PartyGame, w: Float, now: Long, dtMs: Float) {
        val y = bossY()
        val target = g.bossHp / g.bossMaxHp.toFloat()
        hpShown += (target - hpShown) * min(1f, dtMs / 120f)
        val sinceHit = now - g.bossHitAt
        val shake = if (g.bossHitAt > 0 && sinceHit < 400) sin(sinceHit / 22f) * dp(6f) * (1f - sinceHit / 400f) else 0f
        val fx = dp(52f) + shake
        kit.fill.color = Color.argb(190, 40, 0, 0)
        c.drawCircle(fx, y, dp(30f), kit.fill)
        kit.stroke.strokeWidth = dp(2f)
        kit.stroke.color = RED
        c.drawCircle(fx, y, dp(30f), kit.stroke)
        kit.emoji(c, g.boss.face, fx, y, dp(40f) * (if (sinceHit < 150) 0.85f else 1f))

        val x0 = dp(94f)
        val x1 = w - dp(24f)
        kit.text(c, g.boss.name.get(kit.hinglish), (x0 + x1) / 2f, y - dp(14f), sp(15f), Color.WHITE)
        kit.rect.set(x0, y + dp(2f), x1, y + dp(18f))
        kit.fill.color = Color.argb(200, 20, 20, 20)
        c.drawRoundRect(kit.rect, dp(8f), dp(8f), kit.fill)
        kit.rect.right = x0 + (x1 - x0) * hpShown.coerceIn(0f, 1f)
        kit.fill.color = if (hpShown > 0.5f) Color.parseColor("#E53935") else Color.parseColor("#FF6F00")
        c.drawRoundRect(kit.rect, dp(8f), dp(8f), kit.fill)
        kit.rect.right = x1
        kit.stroke.color = Color.argb(160, 255, 255, 255)
        kit.stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(kit.rect, dp(8f), dp(8f), kit.stroke)

        if (g.combo >= 2) {
            if (comboFor != g.combo) {
                comboFor = g.combo
                comboStr = "COMBO ×" + (if (g.comboMultiplier % 1f == 0f) g.comboMultiplier.toInt().toString() else g.comboMultiplier.toString()) + " 🔥"
            }
            kit.text(c, comboStr, (x0 + x1) / 2f, y + dp(34f), sp(14f), GOLD, (200 + 55 * sin(now / 120f)).toInt())
        }
    }

    private fun drawBossIntro(c: Canvas, g: PartyGame, t: Long, w: Float, h: Float) {
        val p = (t / 600f).coerceAtMost(1f)
        kit.fill.color = Color.BLACK
        kit.fill.alpha = 160
        c.drawRect(0f, 0f, w, h, kit.fill)
        val cy = h * 0.42f
        val hi = kit.hinglish
        val head = (if (hi) "BOSS " else "BOSS ") + (g.bossIndex + 1) + if (g.lap > 0) "  ★${g.lap}" else ""
        kit.text(c, head, w / 2f, cy - dp(120f), sp(20f), MUTED, (255 * p).toInt())
        kit.emoji(c, g.boss.face, w / 2f, cy - dp(30f), dp(120f) * easeOutBack(p))
        kit.text(c, g.boss.name.get(hi), w / 2f, cy + dp(60f), sp(36f), RED, 255, 0.5f + 0.5f * easeOutBack(p))
        val p2 = ((t - 500) / 400f).coerceIn(0f, 1f)
        val line = if (g.lap > 0) (if (hi) "Aur bhi taakatwar!" else "Back and stronger!")
        else (if (hi) "Jeeto, combo banao, haraao!" else "Win games to hit it. Combos hit harder!")
        kit.text(c, line, w / 2f, cy + dp(100f), sp(15f), Color.WHITE, (255 * p2).toInt())
        kit.text(c, "HP ${g.bossMaxHp}", w / 2f, cy + dp(128f), sp(14f), GOLD, (255 * p2).toInt())
    }

    private fun drawBossDown(c: Canvas, g: PartyGame, t: Long, w: Float, h: Float) {
        val p = (t / 500f).coerceAtMost(1f)
        kit.fill.color = Color.BLACK
        kit.fill.alpha = (140 * p).toInt()
        c.drawRect(0f, 0f, w, h, kit.fill)
        val cy = h * 0.42f
        kit.emoji(c, g.boss.face, w / 2f, cy - dp(40f) + p * dp(40f), dp(110f) * (1f - 0.4f * p), (255 * (1f - p * 0.6f)).toInt(), rotate = p * 200f)
        kit.emoji(c, "💥", w / 2f, cy - dp(20f), dp(140f) * p, (255 * (1f - p)).toInt())
        val hi = kit.hinglish
        kit.text(c, if (hi) "BOSS HAAR GAYA!" else "BOSS DEFEATED!", w / 2f, cy + dp(60f), sp(36f), GOLD, 255, 0.5f + 0.5f * easeOutBack(p))
        val p2 = ((t - 400) / 400f).coerceIn(0f, 1f)
        kit.text(c, "+1 ❤️", w / 2f, cy + dp(100f), sp(20f), RED, (255 * p2).toInt())
    }

    private fun drawSpeedUp(c: Canvas, g: PartyGame, t: Long, w: Float, h: Float) {
        val p = (t / 350f).coerceAtMost(1f)
        val cy = h * 0.42f
        val shake = sin(t / 30f) * dp(4f) * (1f - p)
        kit.emoji(c, "⚡", w / 2f + shake, cy - dp(70f), dp(70f) * easeOutBack(p))
        kit.text(c, if (kit.hinglish) "AUR TEZ!" else "SPEED UP!", w / 2f + shake, cy, sp(48f), GOLD, 255, 0.5f + 0.5f * easeOutBack(p))
        kit.text(c, speedStr, w / 2f, cy + dp(48f), sp(24f), Color.WHITE)
    }

    private fun drawGameOver(c: Canvas, t: Long, w: Float, h: Float) {
        val p = (t / 400f).coerceAtMost(1f)
        kit.fill.color = Color.BLACK
        kit.fill.alpha = (p * 175).toInt()
        c.drawRect(0f, 0f, w, h, kit.fill)
        val cy = h * 0.38f
        kit.text(c, if (kit.hinglish) "KHEL KHATAM" else "GAME OVER", w / 2f, cy - dp(30f), sp(40f), RED, 255, easeOutBack(p))
        val a = (255 * ((t - 250) / 400f).coerceIn(0f, 1f)).toInt()
        kit.text(c, endLine1, w / 2f, cy + dp(22f), sp(22f), Color.WHITE, a)
        kit.text(c, endLine2, w / 2f, cy + dp(56f), sp(17f), if (game?.newBest == true) GOLD else SOFT_WHITE, a)
        kit.text(c, endLine3, w / 2f, cy + dp(86f), sp(14f), BLUE, a)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = min(1f, t) - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }
}
