package dev.nocturne.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nocturne.player.NocturneApp
import dev.nocturne.player.data.Video
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val services = app as NocturneApp
    val dao = services.database.dao()
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(0)
    val section = MutableStateFlow("all")
    val collection = MutableStateFlow(-1L)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val collections = dao.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val videos = combine(dao.videos(), dao.allItems(), query, sort, section, collection) { values ->
        @Suppress("UNCHECKED_CAST") val list = values[0] as List<Video>
        @Suppress("UNCHECKED_CAST") val items = values[1] as List<dev.nocturne.player.data.CollectionItem>
        val q = values[2] as String; val order = values[3] as Int; val filter = values[4] as String; val id = values[5] as Long
        val members = items.filter { it.collectionId == id }.associate { it.videoUri to it.ordinal }
        val filtered = list.filter { it.name.contains(q, true) && when (filter) {
            "continue" -> it.position > 0 && (it.duration == 0L || it.position < it.duration - 3000)
            "recent" -> it.watched > 0
            "collection" -> it.uri in members
            else -> true
        } }
        when {
            filter == "collection" -> filtered.sortedBy { members[it.uri] }
            filter == "recent" || filter == "continue" -> filtered.sortedByDescending { it.watched }
            order == 1 -> filtered.sortedByDescending { it.modified }
            order == 2 -> filtered.sortedByDescending { it.duration }
            else -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun scan() { if (busy.value) return; viewModelScope.launch {
        busy.value = true
        try { services.library.scan { message.value = it } }
        catch (e: Exception) { message.value = e.localizedMessage ?: "Ошибка сканирования" }
        finally { busy.value = false }
    } }
}
