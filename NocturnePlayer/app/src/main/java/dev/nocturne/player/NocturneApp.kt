package dev.nocturne.player

import android.app.Application
import androidx.room.Room
import dev.nocturne.player.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NocturneApp : Application() {
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, PlayerDatabase::class.java, "nocturne.db").build() }
    val preferences by lazy { PreferencesRepository(this) }
    val library by lazy { LibraryRepository(this, database.dao()) }
    val subtitles by lazy { SubtitleRepository(this, database.dao()) }
}
