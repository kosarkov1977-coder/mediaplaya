@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import dev.nocturne.player.NocturneApp
import dev.nocturne.player.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NocturneApp
    private val dao = app.database.dao()
    val trackSelector = DefaultTrackSelector(app)
    val player = ExoPlayer.Builder(app, DefaultRenderersFactory(app).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    private val session = MediaSession.Builder(app, player).build()
    val preferences = app.preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val video = MutableStateFlow<Video?>(null)
    val options = MutableStateFlow(VideoOptions(""))
    val files = MutableStateFlow<List<SubtitleFile>>(emptyList())
    val timeline = MutableStateFlow<SubtitleTimeline?>(null)
    val error = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    var queue = listOf<String>(); private set
    private var opening: Job? = null
    private var subtitleJob: Job? = null
    private var pendingSeek = 0L
    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error.value = "Не удалось воспроизвести файл (${e.errorCodeName}). Проверьте доступ и поддержку кодека на устройстве."
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && preferences.value.autoNext) next(1)
            }
            override fun onTracksChanged(tracks: Tracks) {
                val o = options.value
                fun restore(type: Int, groupIndex: Int, track: Int) {
                    val group = tracks.groups.filter { it.type == type }.getOrNull(groupIndex) ?: return
                    if (track !in 0 until group.length || !group.isTrackSupported(track) || group.isTrackSelected(track)) return
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track)).build()
                }
                restore(C.TRACK_TYPE_AUDIO, o.audioGroup, o.audioTrack)
                if (o.subtitlesEnabled && o.subtitleUri.isBlank()) restore(C.TRACK_TYPE_TEXT, o.textGroup, o.textTrack)
            }
        })
        viewModelScope.launch { while (isActive) { delay(3000); checkpoint() } }
    }
    fun start(uri: String, items: List<String>) {
        if (video.value?.uri == uri) return
        queue = if (uri in items) items else listOf(uri)
        open(uri)
    }
    private fun open(uri: String) {
        checkpoint()
        opening?.cancel(); subtitleJob?.cancel(); timeline.value = null; player.stop()
        opening = viewModelScope.launch {
            loading.value = true
            try {
                val item = dao.video(uri) ?: app.library.importVideo(Uri.parse(uri))
                val pref = app.preferences.values.first()
                val settings = dao.options(uri) ?: VideoOptions(uri, textSize = pref.subtitleSize, textColor = pref.color,
                    background = pref.background, font = pref.font, bottom = pref.bottom, preserveStyles = pref.preserve, speed = pref.speed)
                video.value = item; options.value = settings
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build()).build())
                player.setPlaybackSpeed(settings.speed)
                pendingSeek = if (item.duration > 0 && item.position >= item.duration - 3000) 0 else item.position
                player.seekTo(pendingSeek); player.prepare(); player.play()
                files.value = dao.subtitles(uri)
                try { files.value = app.subtitles.discover(item) } catch (e: Exception) { error.value = "Автопоиск субтитров: ${e.localizedMessage}" }
                if (settings.subtitleUri.isNotBlank()) selectSubtitle(settings.subtitleUri, settings.subtitlesEnabled)
                else if (settings.autoSelectSubtitles && files.value.isNotEmpty()) selectSubtitle(files.value.first().uri, settings.subtitlesEnabled)
                else updateTextTracks()
            } catch (e: Exception) { error.value = e.localizedMessage ?: "Не удалось открыть видео" }
            finally { loading.value = false }
        }
    }
    fun next(direction: Int) {
        val index = queue.indexOf(video.value?.uri)
        queue.getOrNull(index + direction)?.let(::open)
    }
    fun checkpoint() {
        val item = video.value ?: return
        if (player.currentMediaItem?.mediaId != item.uri) return
        val duration = player.duration.takeIf { it > 0 } ?: item.duration
        val position = player.currentPosition.coerceAtLeast(0)
        val settings = options.value
        app.persistenceScope.launch { dao.progress(item.uri, position, duration, System.currentTimeMillis()); dao.putOptions(settings) }
    }
    fun update(transform: (VideoOptions) -> VideoOptions) {
        options.value = transform(options.value)
        player.setPlaybackSpeed(options.value.speed)
        updateTextTracks()
        val snapshot = options.value
        viewModelScope.launch { dao.putOptions(snapshot) }
    }
    private fun updateTextTracks() {
        val o = options.value
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !o.subtitlesEnabled || o.subtitleUri.isNotBlank()).build()
    }
    fun selectSubtitle(uri: String, enabled: Boolean = true) {
        subtitleJob?.cancel(); timeline.value = null
        update { it.copy(subtitleUri = uri, subtitlesEnabled = enabled, autoSelectSubtitles = false) }
        if (uri.isBlank()) return
        val file = files.value.find { it.uri == uri } ?: run { error.value = "Файл субтитров больше недоступен"; return }
        subtitleJob = viewModelScope.launch { try { timeline.value = app.subtitles.load(file) }
            catch (e: Exception) { error.value = "${file.name}: ${e.localizedMessage}" } }
    }
    fun attach(uris: List<Uri>) { viewModelScope.launch {
        val item = video.value ?: return@launch
        var selected: String? = null
        for (uri in uris) { try { selected = app.subtitles.attach(item.uri, uri).uri } catch (e: Exception) { error.value = e.localizedMessage ?: "Ошибка субтитров" } }
        files.value = dao.subtitles(item.uri); selected?.let(::selectSubtitle)
    } }
    fun encoding(file: SubtitleFile, encoding: String) { viewModelScope.launch {
        dao.putSubtitle(file.copy(encoding = encoding)); files.value = dao.subtitles(file.videoUri); selectSubtitle(file.uri)
    } }
    fun remove(file: SubtitleFile) { viewModelScope.launch {
        dao.removeSubtitle(file.videoUri, file.uri); files.value = dao.subtitles(file.videoUri)
        if (options.value.subtitleUri == file.uri) selectSubtitle("")
    } }
    override fun onCleared() { session.release(); player.release() }
}
