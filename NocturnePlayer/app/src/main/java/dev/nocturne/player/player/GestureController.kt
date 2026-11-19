@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player.player

import android.app.Activity
import android.media.AudioManager
import android.content.Context
import android.view.*
import android.widget.Button
import android.widget.SeekBar
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import kotlin.math.abs

class GestureController(
    private val activity: Activity, private val player: ExoPlayer, private val root: View,
    private val enabled: () -> Boolean, private val locked: () -> Boolean,
    private val tap: () -> Unit, private val zoom: (Float) -> Unit, private val feedback: (String) -> Unit
) {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var accept = false
    private var mode = 0
    private var startX = 0f; private var startY = 0f
    private var startPosition = 0L; private var seekPosition = 0L
    private var startVolume = 0; private var startBrightness = .5f
    private var scale = 1f; private var scaled = false
    fun resetZoom() { scale = 1f }
    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (!locked()) tap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (enabled() && !locked()) { val delta = if (e.x < root.width / 2) -10000 else 10000; player.seekTo((player.currentPosition + delta).coerceIn(0, maxDuration())); feedback(if (delta < 0) "−10 секунд" else "+10 секунд") }
            return true
        }
    })
    private val scaler = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!enabled() || locked()) return false
            scaled = true; mode = 4; scale = (scale * detector.scaleFactor).coerceIn(.5f, 3f); zoom(scale); feedback("Масштаб ${"%.2f".format(scale)}×"); return true
        }
    })
    private fun maxDuration() = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    private fun isControl(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect(); if (!view.getGlobalVisibleRect(rect) || !rect.contains(x.toInt(), y.toInt())) return false
        if (view is Button || view is SeekBar || view is DefaultTimeBar || (view.isClickable && view !is ViewGroup && view !is TextureView && view !is SurfaceView)) return true
        return view is ViewGroup && (0 until view.childCount).any { isControl(view.getChildAt(it), x, y) }
    }
    fun dispatch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            accept = !isControl(root, event.rawX, event.rawY)
            mode = 0; scaled = false
            startX = event.x; startY = event.y
            startPosition = player.currentPosition; seekPosition = startPosition
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f
        }
        if (!accept) return false
        if (locked()) return true
        scaler.onTouchEvent(event)
        detector.onTouchEvent(event)
        if (enabled() && event.actionMasked == MotionEvent.ACTION_MOVE && !scaled && event.pointerCount == 1) {
            val dx = event.x - startX; val dy = startY - event.y
            if (mode == 0 && maxOf(abs(dx), abs(dy)) > 28 * activity.resources.displayMetrics.density) mode = if (abs(dx) > abs(dy)) 1 else if (startX < root.width / 2) 2 else 3
            when (mode) {
                1 -> { seekPosition = (startPosition + (dx / root.width * 120000).toLong()).coerceIn(0, maxDuration()); feedback("Перемотка: ${dev.nocturne.player.ui.durationText(seekPosition)}") }
                2 -> { val value = (startBrightness + dy / root.height).coerceIn(.02f, 1f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }; feedback("Яркость ${(value * 100).toInt()}%") }
                3 -> { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val value = (startVolume + dy / root.height * max).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); feedback("Громкость ${value * 100 / max.coerceAtLeast(1)}%") }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && mode == 1) player.seekTo(seekPosition)
        return true
    }
}
