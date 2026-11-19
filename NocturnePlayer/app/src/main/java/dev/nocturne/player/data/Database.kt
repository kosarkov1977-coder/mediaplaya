package dev.nocturne.player.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "videos")
data class Video(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long = 0,
    val size: Long = 0,
    val modified: Long = 0,
    val parent: String = "",
    val position: Long = 0,
    val watched: Long = 0
)

@Entity(tableName = "subtitle_files", primaryKeys = ["videoUri", "uri"])
data class SubtitleFile(val videoUri: String, val uri: String, val name: String, val encoding: String = "auto")

@Entity(tableName = "video_options")
data class VideoOptions(
    @PrimaryKey val videoUri: String,
    val subtitleUri: String = "",
    val subtitlesEnabled: Boolean = true,
    val autoSelectSubtitles: Boolean = true,
    val delayMs: Long = 0,
    val textSize: Float = 22f,
    val textColor: Int = -1,
    val background: Int = 0x80000000.toInt(),
    val font: String = "sans-serif",
    val bottom: Float = .08f,
    val preserveStyles: Boolean = true,
    val speed: Float = 1f,
    val aspect: Int = 0,
    val audioGroup: Int = -1,
    val audioTrack: Int = -1,
    val textGroup: Int = -1,
    val textTrack: Int = -1
)

@Entity(tableName = "collections")
data class Collection(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val kind: String)
@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "videoUri"])
data class CollectionItem(val collectionId: Long, val videoUri: String, val ordinal: Int)
@Entity(tableName = "folders")
data class ScanFolder(@PrimaryKey val uri: String, val name: String)

@Dao
interface PlayerDao {
    @Query("SELECT * FROM videos") fun videos(): Flow<List<Video>>
    @Query("SELECT * FROM videos WHERE uri=:uri") suspend fun video(uri: String): Video?
    @Upsert suspend fun putVideo(video: Video)
    @Query("UPDATE videos SET position=:position, duration=:duration, watched=:time WHERE uri=:uri")
    suspend fun progress(uri: String, position: Long, duration: Long, time: Long)
    @Query("UPDATE videos SET position=0, watched=0") suspend fun clearHistory()
    @Query("SELECT * FROM subtitle_files WHERE videoUri=:uri") suspend fun subtitles(uri: String): List<SubtitleFile>
    @Upsert suspend fun putSubtitle(file: SubtitleFile)
    @Query("DELETE FROM subtitle_files WHERE videoUri=:video AND uri=:uri") suspend fun removeSubtitle(video: String, uri: String)
    @Query("SELECT * FROM video_options WHERE videoUri=:uri") suspend fun options(uri: String): VideoOptions?
    @Upsert suspend fun putOptions(options: VideoOptions)
    @Query("SELECT * FROM folders") fun folders(): Flow<List<ScanFolder>>
    @Query("SELECT * FROM folders") suspend fun folderList(): List<ScanFolder>
    @Upsert suspend fun putFolder(folder: ScanFolder)
    @Query("DELETE FROM folders WHERE uri=:uri") suspend fun removeFolder(uri: String)
    @Query("SELECT * FROM collections ORDER BY name") fun collections(): Flow<List<Collection>>
    @Insert suspend fun addCollection(collection: Collection): Long
    @Query("DELETE FROM collections WHERE id=:id") suspend fun deleteCollection(id: Long)
    @Query("DELETE FROM collection_items WHERE collectionId=:id") suspend fun clearCollection(id: Long)
    @Query("SELECT * FROM collection_items WHERE collectionId=:id ORDER BY ordinal") suspend fun items(id: Long): List<CollectionItem>
    @Query("SELECT * FROM collection_items") fun allItems(): Flow<List<CollectionItem>>
    @Upsert suspend fun putItem(item: CollectionItem)
    @Query("DELETE FROM collection_items WHERE collectionId=:id AND videoUri=:uri") suspend fun removeItem(id: Long, uri: String)
}

@Database(entities = [Video::class, SubtitleFile::class, VideoOptions::class, Collection::class, CollectionItem::class, ScanFolder::class], version = 1, exportSchema = true)
abstract class PlayerDatabase : RoomDatabase() { abstract fun dao(): PlayerDao }
