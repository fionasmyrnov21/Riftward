package com.riftwardechoprotocol.k7m4x9

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import com.riftwardechoprotocol.k7m4x9.ui.GameView

class MainActivity : Activity() {
    private lateinit var game: GameView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        game = GameView(this); setContentView(game)
        window.decorView.post { hideBars() }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { game.back() }
    }
    private fun hideBars() { window.decorView.windowInsetsController?.apply { hide(WindowInsets.Type.systemBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    override fun onResume() { super.onResume(); if (::game.isInitialized) game.resumeGame() }
    override fun onPause() { if (::game.isInitialized) game.pauseGame(); super.onPause() }
    override fun onDestroy() { if (::game.isInitialized) game.closeGame(); super.onDestroy() }
    @Deprecated("Android compatibility callback")
    override fun onBackPressed() { if (::game.isInitialized) game.back() }
}
