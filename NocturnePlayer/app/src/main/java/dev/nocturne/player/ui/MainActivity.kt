package dev.nocturne.player.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.nocturne.player.NocturneApp
import dev.nocturne.player.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_host)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        lifecycleScope.launch {
            (application as NocturneApp).preferences.values.map { it.theme }.distinctUntilChanged().collect { theme ->
                AppCompatDelegate.setDefaultNightMode(when (theme) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; else -> AppCompatDelegate.MODE_NIGHT_YES })
            }
        }
    }
}
