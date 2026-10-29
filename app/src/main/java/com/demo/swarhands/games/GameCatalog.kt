package com.demo.swarhands.games

import android.app.Activity
import com.demo.swarhands.MainActivity
import com.demo.swarhands.games.bullseye.BullseyeActivity
import com.demo.swarhands.games.cricket.CricketActivity
import com.demo.swarhands.games.party.PartyActivity
import com.demo.swarhands.games.rps.RpsActivity
import com.demo.swarhands.games.signs.SignActivity

/** One row on the home screen. [activity] = null means "coming soon". */
data class GameEntry(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val activity: Class<out Activity>?
)

object GameCatalog {
    val music = GameEntry("🎹", "Air Sargam", "Play Sa Re Ga Ma with finger pinches", MainActivity::class.java)

    /** Add new games here and they show up on the home screen automatically. */
    val games = listOf(
        GameEntry("✌️", "Stone Paper Scissors", "Beat the AI glove · Best of 3", RpsActivity::class.java),
        GameEntry("🎯", "Bullseye", "Bow or slingshot · Pull, aim, let go", BullseyeActivity::class.java),
        GameEntry("🏏", "Gully Cricket", "Swing your hand like a bat · Chase or Super Over", CricketActivity::class.java),
        GameEntry("🖐️", "Sign Says", "Watch the hand signs, repeat them in order", SignActivity::class.java),
        GameEntry("🎉", "Haath Party", "Quick 5-second hand games · Do what it says, fast!", PartyActivity::class.java),
        GameEntry("🎼", "Swar Hero", "Pinch the falling notes in time", null),
        GameEntry("🍉", "Air Slice", "Swipe your finger to slice fruit", null),
        GameEntry("🎈", "Balloon Pop", "Pinch balloons before they fly away", null)
    )
}
