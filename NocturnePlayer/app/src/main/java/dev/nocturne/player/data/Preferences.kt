package dev.nocturne.player.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("preferences")
data class Preferences(
    val gestures: Boolean = true, val autoNext: Boolean = true,
    val speed: Float = 1f, val theme: String = "dark", val subtitleSize: Float = 22f,
    val color: Int = -1, val background: Int = 0x80000000.toInt(),
    val font: String = "sans-serif", val bottom: Float = .08f, val preserve: Boolean = true
)
class PreferencesRepository(private val context: Context) {
    val values = context.store.data.map { p -> Preferences(
        p[booleanPreferencesKey("gestures")] ?: true, p[booleanPreferencesKey("next")] ?: true,
        p[floatPreferencesKey("speed")] ?: 1f, p[stringPreferencesKey("theme")] ?: "dark",
        p[floatPreferencesKey("size")] ?: 22f, p[intPreferencesKey("color")] ?: -1,
        p[intPreferencesKey("background")] ?: 0x80000000.toInt(),
        p[stringPreferencesKey("font")] ?: "sans-serif", p[floatPreferencesKey("bottom")] ?: .08f,
        p[booleanPreferencesKey("preserve")] ?: true
    ) }
    suspend fun save(p: Preferences) { context.store.edit {
        it[booleanPreferencesKey("gestures")] = p.gestures
        it[booleanPreferencesKey("next")] = p.autoNext
        it[floatPreferencesKey("speed")] = p.speed
        it[stringPreferencesKey("theme")] = p.theme
        it[floatPreferencesKey("size")] = p.subtitleSize
        it[intPreferencesKey("color")] = p.color
        it[intPreferencesKey("background")] = p.background
        it[stringPreferencesKey("font")] = p.font
        it[floatPreferencesKey("bottom")] = p.bottom
        it[booleanPreferencesKey("preserve")] = p.preserve
    } }
}
