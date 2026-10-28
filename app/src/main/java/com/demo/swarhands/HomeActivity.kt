package com.demo.swarhands

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.demo.swarhands.games.GameCatalog
import com.demo.swarhands.games.GameEntry

/** Home hub: the music mode plus the list of hand-gesture games. */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        addEntry(findViewById(R.id.musicContainer), GameCatalog.music)
        val games = findViewById<LinearLayout>(R.id.gamesContainer)
        GameCatalog.games.forEach { addEntry(games, it) }
    }

    private fun addEntry(parent: LinearLayout, entry: GameEntry) {
        val card = layoutInflater.inflate(R.layout.item_game, parent, false)
        card.findViewById<TextView>(R.id.emoji).text = entry.emoji
        card.findViewById<TextView>(R.id.title).text = entry.title
        card.findViewById<TextView>(R.id.subtitle).text = entry.subtitle
        val badge = card.findViewById<TextView>(R.id.badge)

        val target = entry.activity
        if (target != null) {
            badge.text = getString(R.string.badge_play)
            badge.setBackgroundResource(R.drawable.bg_badge_play)
            badge.setTextColor(Color.BLACK)
            card.setOnClickListener { startActivity(Intent(this, target)) }
        } else {
            badge.text = getString(R.string.badge_soon)
            badge.setBackgroundResource(R.drawable.bg_badge_soon)
            badge.setTextColor(Color.WHITE)
            card.alpha = 0.55f
        }
        parent.addView(card)
    }
}
