@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.ui.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import dev.nocturne.player.R
import dev.nocturne.player.databinding.ActivityPlayerBinding
import dev.nocturne.player.player.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private val vm: PlayerViewModel by viewModels()
    private lateinit var gestures: GestureController
    private var locked = false
    private var lastCues: List<Cue> = emptyList()
    private var wasPlaying = false
    private lateinit var playerListener: Player.Listener
    private val hideFeedback = Runnable { b.feedback.visibility = View.GONE }
    private val subtitles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val readable = uris.filter { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }
            catch (e: Exception) { showFeedback("Нет постоянного доступа к $uri"); false } }
        vm.attach(readable)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, b.root).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets -> val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()); view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom); insets }
        b.player.player = vm.player
        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePipParams() }
            override fun onVideoSizeChanged(videoSize: VideoSize) { applyAspect(); updatePipParams() }
        }
        vm.player.addListener(playerListener)
        b.player.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible ->
            val show = visible == View.VISIBLE && !locked && !isInPictureInPictureMode
            b.top.visibility = if (show) View.VISIBLE else View.GONE; b.bottom.visibility = if (show) View.VISIBLE else View.GONE
        })
        // Keep the stock time bar clear of the extra action row.
        b.player.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.setPadding(0, 0, 0, (64 * resources.displayMetrics.density).toInt())
        b.back.setOnClickListener { finish() }; b.previous.setOnClickListener { vm.next(-1) }; b.next.setOnClickListener { vm.next(1) }
        b.subtitleButton.setOnClickListener { subtitleMenu() }; b.audio.setOnClickListener { tracks(C.TRACK_TYPE_AUDIO) }
        b.speed.setOnClickListener { speed() }; b.aspect.setOnClickListener { aspect() }; b.pip.setOnClickListener { enterPip() }
        b.lock.setOnClickListener { setLocked(true) }; b.unlock.setOnClickListener { setLocked(false) }; b.adjust.setOnClickListener { adjust() }
        gestures = GestureController(this, vm.player, b.root, { vm.preferences.value.gestures }, { locked },
            { if (b.player.isControllerFullyVisible) b.player.hideController() else b.player.showController() },
            { value -> b.player.videoSurfaceView?.apply { scaleX = value; scaleY = value } }, ::showFeedback)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (locked) setLocked(false) else finish() } })
        val uri = intent.getStringExtra("uri") ?: intent.data?.toString()
        if (uri == null) { Toast.makeText(this, "Видео не выбрано", Toast.LENGTH_LONG).show(); finish(); return }
        vm.start(uri, PlaybackQueue.uris)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { vm.video.collect { b.title.text = it?.name ?: "Nocturne" } }
            launch { vm.loading.collect { loading ->
                listOf(b.previous, b.next, b.subtitleButton, b.audio, b.speed, b.aspect).forEach { it.isEnabled = !loading }
            } }
            launch { vm.error.collect { if (it.isNotBlank()) { MaterialAlertDialogBuilder(this@PlayerActivity).setTitle("Сообщение плеера").setMessage(it).setPositiveButton("Закрыть", null).show(); vm.error.value = "" } } }
            launch { vm.options.collect { o ->
                b.speed.text = "${o.speed}×"
                applyAspect()
                applySubtitleStyle(); lastCues = emptyList(); b.subtitles.setCues(null)
            } }
            launch { while (isActive) {
                val o = vm.options.value
                val raw = if (o.subtitlesEnabled && o.subtitleUri.isNotBlank()) vm.timeline.value?.at(vm.player.currentPosition, o.delayMs).orEmpty() else emptyList()
                val cues = if (o.preserveStyles) raw else raw.map { cue -> cue.buildUpon()
                    .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET)
                    .setPosition(Cue.DIMEN_UNSET).setPositionAnchor(Cue.TYPE_UNSET)
                    .setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).clearWindowColor()
                    .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build() }
                if (cues != lastCues) { b.subtitles.setCues(cues); lastCues = cues }
                if (vm.player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(50)
            } }
        } }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::gestures.isInitialized && !isInPictureInPictureMode && window.decorView.hasWindowFocus() && gestures.dispatch(event)) return true
        return super.dispatchTouchEvent(event)
    }
    private fun applySubtitleStyle() {
        val o = vm.options.value
        listOfNotNull(b.subtitles, b.player.subtitleView).forEach { view ->
            view.setApplyEmbeddedStyles(o.preserveStyles); view.setApplyEmbeddedFontSizes(o.preserveStyles)
            view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, o.textSize); view.setBottomPaddingFraction(o.bottom)
            view.setStyle(CaptionStyleCompat(o.textColor, o.background, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.create(o.font, Typeface.NORMAL)))
        }
    }
    private fun setLocked(value: Boolean) {
        locked = value; b.unlock.visibility = if (value) View.VISIBLE else View.GONE
        b.player.useController = !value
        if (value) { b.player.hideController(); b.top.visibility = View.GONE; b.bottom.visibility = View.GONE } else b.player.showController()
    }
    private fun applyAspect() {
        val mode = vm.options.value.aspect
        b.player.resizeMode = when (mode) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; 2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
        val frame = b.player.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        val size = vm.player.videoSize
        frame?.setAspectRatio(when (mode) { 3 -> 16f / 9; 4 -> 4f / 3; else -> if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f })
    }
    private fun showFeedback(text: String) { b.feedback.text = text; b.feedback.visibility = View.VISIBLE; b.feedback.removeCallbacks(hideFeedback); b.feedback.postDelayed(hideFeedback, 1200) }
    private fun subtitleMenu() {
        val o = vm.options.value
        val entries = arrayOf("Добавить файлы…", if (o.subtitlesEnabled) "Выключить субтитры" else "Включить субтитры", "Встроенные дорожки…", "Задержка: ${"%+.1f".format(o.delayMs / 1000.0)} с", "Оформление…", "Управление файлами…") + vm.files.value.map { (if (it.uri == o.subtitleUri) "✓  " else "") + it.name }
        MaterialAlertDialogBuilder(this).setTitle("Субтитры").setItems(entries) { _, i -> when (i) {
            0 -> subtitles.launch(arrayOf("*/*"))
            1 -> vm.update { it.copy(subtitlesEnabled = !it.subtitlesEnabled) }
            2 -> tracks(C.TRACK_TYPE_TEXT)
            3 -> subtitleDelay()
            4 -> SubtitleStyleDialog.show(this, vm.options.value) { options -> vm.update { options } }
            5 -> manageSubtitles()
            else -> vm.selectSubtitle(vm.files.value[i - 6].uri)
        } }.show()
    }
    private fun subtitleDelay() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        val label = TextView(this).apply { textSize = 24f; gravity = Gravity.CENTER }
        fun render() { label.text = "${"%+.1f".format(vm.options.value.delayMs / 1000.0)} с" }
        render(); box.addView(label)
        box.addView(TextView(this).apply { text = "Плюс — показать позже. Минус — раньше. Задержка применяется к внешним субтитрам." })
        val row = LinearLayout(this)
        listOf("−0,1 с" to -100L, "+0,1 с" to 100L).forEach { (title, delta) -> row.addView(Button(this).apply { text = title; setOnClickListener { vm.update { it.copy(delayMs = (it.delayMs + delta).coerceIn(-600000, 600000)) }; render() } }, LinearLayout.LayoutParams(0, -2, 1f)) }
        box.addView(row)
        MaterialAlertDialogBuilder(this).setTitle("Синхронизация").setView(box).setPositiveButton("Готово", null).setNeutralButton("Сбросить") { _, _ -> vm.update { it.copy(delayMs = 0) } }.show()
    }
    private fun manageSubtitles() {
        val files = vm.files.value
        MaterialAlertDialogBuilder(this).setTitle("Файлы субтитров").setItems(files.map { it.name }.toTypedArray()) { _, i ->
            val file = files[i]
            MaterialAlertDialogBuilder(this).setTitle(file.name).setItems(arrayOf("Кодировка: автоматически", "UTF-8", "Windows-1251", "Windows-1252", "UTF-16", "Отключить файл от видео")) { _, action ->
                if (action == 5) vm.remove(file) else vm.encoding(file, arrayOf("auto", "UTF-8", "windows-1251", "windows-1252", "UTF-16")[action])
            }.show()
        }.setNegativeButton("Закрыть", null).show()
    }
    private fun tracks(type: Int) {
        val groups = vm.player.currentTracks.groups.filter { it.type == type }
        val selections = mutableListOf<Pair<Int, Int>>()
        val labels = mutableListOf("Автоматически")
        groups.forEachIndexed { g, group -> for (t in 0 until group.length) {
            if (group.isTrackSupported(t)) { val f = group.getTrackFormat(t); selections.add(g to t); labels.add("${if (group.isTrackSelected(t)) "✓ " else ""}${f.label ?: f.language ?: "Дорожка ${selections.size}"} · ${f.codecs ?: f.sampleMimeType.orEmpty()}${if (f.channelCount > 0) " · ${f.channelCount} ch" else ""}") }
        } }
        MaterialAlertDialogBuilder(this).setTitle(if (type == C.TRACK_TYPE_AUDIO) "Аудиодорожки" else "Встроенные субтитры").setItems(labels.toTypedArray()) { _, index ->
            if (type == C.TRACK_TYPE_TEXT) vm.selectSubtitle("")
            val builder = vm.player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            if (index > 0) { val (g, t) = selections[index - 1]; builder.setOverrideForType(TrackSelectionOverride(groups[g].mediaTrackGroup, t))
                if (type == C.TRACK_TYPE_AUDIO) vm.update { it.copy(audioGroup = g, audioTrack = t) } else vm.update { it.copy(textGroup = g, textTrack = t) }
            } else if (type == C.TRACK_TYPE_AUDIO) vm.update { it.copy(audioGroup = -1, audioTrack = -1) } else vm.update { it.copy(textGroup = -1, textTrack = -1) }
            vm.player.trackSelectionParameters = builder.build()
        }.show()
    }
    private fun speed() {
        val slider = Slider(this).apply { valueFrom = .25f; valueTo = 3f; stepSize = .05f; value = vm.options.value.speed.coerceIn(.25f, 3f) }
        slider.addOnChangeListener { _, v, _ -> vm.update { it.copy(speed = v) } }
        MaterialAlertDialogBuilder(this).setTitle("Скорость · 0,25×–3×").setView(slider).setPositiveButton("Готово", null).setNeutralButton("1×") { _, _ -> vm.update { it.copy(speed = 1f) } }.show()
    }
    private fun aspect() { MaterialAlertDialogBuilder(this).setTitle("Размер видео").setSingleChoiceItems(arrayOf("По размеру экрана", "Растянуть", "Обрезать", "16:9", "4:3"), vm.options.value.aspect) { d, index ->
        vm.update { it.copy(aspect = index) }; gestures.resetZoom(); b.player.videoSurfaceView?.apply { scaleX = 1f; scaleY = 1f }; d.dismiss()
    }.show() }
    private fun adjust() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        box.addView(TextView(this).apply { text = "Громкость" })
        box.addView(Slider(this).apply { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); valueFrom = 0f; valueTo = max.toFloat(); stepSize = 1f; value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat(); addOnChangeListener { _, v, _ -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.toInt(), 0) } })
        box.addView(TextView(this).apply { text = "Яркость" })
        box.addView(Slider(this).apply { valueFrom = .02f; valueTo = 1f; value = window.attributes.screenBrightness.takeIf { it >= .02f } ?: .5f; addOnChangeListener { _, v, _ -> window.attributes = window.attributes.apply { screenBrightness = v } } })
        MaterialAlertDialogBuilder(this).setTitle("Звук и яркость").setView(box).setPositiveButton("Готово", null).show()
    }
    private fun pipParams(): PictureInPictureParams {
        val size = vm.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(.42f, 2.39f) else 16f / 9
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).apply {
            val rect = android.graphics.Rect()
            if (b.player.getGlobalVisibleRect(rect)) setSourceRectHint(rect)
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(vm.player.isPlaying); setSeamlessResizeEnabled(true) }
        }.build()
    }
    private fun enterPip() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) try { enterPictureInPictureMode(pipParams()) }
        catch (_: IllegalStateException) { showFeedback("PiP сейчас недоступен") }
        else showFeedback("Устройство не поддерживает PiP")
    }
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 31 && !isFinishing && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try { setPictureInPictureParams(pipParams()) } catch (_: IllegalStateException) { /* The activity may be leaving the foreground. */ }
        }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (vm.player.isPlaying) enterPip() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        b.player.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) { b.player.hideController(); b.top.visibility = View.GONE; b.bottom.visibility = View.GONE; b.unlock.visibility = View.GONE; b.feedback.visibility = View.GONE }
        else { if (!locked) b.player.showController() else b.unlock.visibility = View.VISIBLE }
    }
    override fun onStart() { super.onStart(); if (wasPlaying) { vm.player.play(); wasPlaying = false } }
    override fun onPause() { vm.checkpoint(); super.onPause() }
    override fun onStop() { if (!isInPictureInPictureMode) { wasPlaying = vm.player.playWhenReady; vm.player.pause() }; vm.checkpoint(); super.onStop() }
    override fun onDestroy() { b.feedback.removeCallbacks(hideFeedback); if (::playerListener.isInitialized) vm.player.removeListener(playerListener); b.player.player = null; super.onDestroy() }
}
