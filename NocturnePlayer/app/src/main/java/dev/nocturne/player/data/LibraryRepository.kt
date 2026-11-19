package dev.nocturne.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository(private val context: Context, private val dao: PlayerDao) {
    companion object { val extensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv") }
    suspend fun importVideo(uri: Uri, parent: String = ""): Video = withContext(Dispatchers.IO) {
        val existing = dao.video(uri.toString())
        var name = uri.lastPathSegment ?: "Видео"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0) ?: name; size = it.getLong(1) }
        }
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) { 0L } finally { retriever.release() }
        (existing?.copy(name = name, size = size, duration = duration.takeIf { it > 0 } ?: existing.duration,
            parent = parent.ifBlank { existing.parent }) ?: Video(uri.toString(), name, duration, size, parent = parent)).also { dao.putVideo(it) }
    }
    suspend fun scan(onWarning: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.RELATIVE_PATH)
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                    val old = dao.video(uri)
                    dao.putVideo(Video(uri, c.getString(1) ?: "Видео", c.getLong(2), c.getLong(3), c.getLong(4),
                        c.getString(5) ?: "", old?.position ?: 0, old?.watched ?: 0))
                }
            }
        } catch (_: SecurityException) { onWarning("Для полной видеотеки разрешите доступ к видео или добавьте папку.") }
        for (folder in dao.folderList()) {
            try {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uri)) ?: continue
                val stack = ArrayDeque<DocumentFile>(); stack.add(root)
                while (stack.isNotEmpty()) {
                    val dir = stack.removeLast()
                    for (file in dir.listFiles()) {
                        if (file.isDirectory) stack.add(file)
                        else if (file.name?.substringAfterLast('.')?.lowercase() in extensions) {
                            importVideo(file.uri, dir.uri.toString())
                        }
                    }
                }
            } catch (e: Exception) { onWarning("Папка ${folder.name} недоступна: ${e.localizedMessage}") }
        }
    }
}
