@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import dev.nocturne.player.player.SubtitleTimeline
import dev.nocturne.player.player.TimedCues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

class SubtitleRepository(private val context: Context, private val dao: PlayerDao) {
    companion object {
        val extensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")
        fun mime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> throw IllegalArgumentException("Выберите SRT, VTT, ASS, SSA или TTML")
        }
        fun matches(video: String, subtitle: String): Boolean {
            val base = video.substringBeforeLast('.').lowercase(Locale.ROOT)
            val stem = subtitle.substringBeforeLast('.').lowercase(Locale.ROOT)
            return subtitle.substringAfterLast('.').lowercase(Locale.ROOT) in extensions &&
                (stem == base || stem.startsWith("$base.") || stem.startsWith("${base}_"))
        }
    }
    suspend fun attach(video: String, uri: Uri, encoding: String = "auto"): SubtitleFile = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment ?: "subtitles.srt"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        mime(name)
        val file = SubtitleFile(video, uri.toString(), name, encoding)
        load(file) // Validate before committing the link.
        dao.putSubtitle(file)
        file
    }
    suspend fun discover(video: Video): List<SubtitleFile> = withContext(Dispatchers.IO) {
        // MediaStore grants do not grant arbitrary sibling access. Match video names only inside granted trees.
        val candidates = mutableListOf<DocumentFile>()
        for (folder in dao.folderList()) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uri)) ?: continue
            val stack = ArrayDeque<DocumentFile>(); stack.add(root)
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val children = dir.listFiles()
                fun documentKey(uri: Uri): String? = try { uri.authority + ":" + DocumentsContract.getDocumentId(uri) } catch (_: IllegalArgumentException) { null }
                val videoKey = documentKey(Uri.parse(video.uri))
                val sameParent = dir.uri.toString() == video.parent || children.any { it.uri.toString() == video.uri || (videoKey != null && documentKey(it.uri) == videoKey) }
                val relative = try { DocumentsContract.getDocumentId(dir.uri).substringAfter(':').trim('/') } catch (_: IllegalArgumentException) { "" }
                val mediaStoreParent = video.parent.isNotBlank() && !video.parent.startsWith("content:") && relative == video.parent.trim('/')
                if (sameParent || (mediaStoreParent && children.any { it.name == video.name })) {
                    candidates.addAll(children.filter { !it.isDirectory && matches(video.name, it.name ?: "") })
                }
                children.filter { it.isDirectory }.forEach(stack::add)
            }
        }
        for (file in candidates.distinctBy { it.uri }) {
            dao.putSubtitle(SubtitleFile(video.uri, file.uri.toString(), file.name ?: "Субтитры"))
        }
        dao.subtitles(video.uri)
    }
    suspend fun load(file: SubtitleFile): SubtitleTimeline = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(Uri.parse(file.uri))?.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = it.read(buffer); if (n < 0) break
                require(output.size() + n <= 16 * 1024 * 1024) { "Файл субтитров больше 16 МБ" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: error("Нет доступа к субтитрам")
        val text = if (file.encoding != "auto") bytes.toString(Charset.forName(file.encoding)) else when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16BE)
            else -> try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: Exception) { bytes.toString(Charset.forName("windows-1251")) }
        }
        var clean = text.trimStart('\uFEFF').replace(Regex("(?i)(<\\?xml[^>]*encoding=[\"'])[^\"']+"), "$1UTF-8")
        if (mime(file.name) == MimeTypes.TEXT_SSA) {
            // SsaParser's header lookahead can skip the last field before the next section.
            // A blank separator preserves that field for valid files without section spacing.
            clean = clean.replace("\r\n", "\n").replace('\r', '\n')
                .replace(Regex("(?m)(?<!\\n)\\n(?=\\[)"), "\n\n")
        }
        val normalized = clean.toByteArray(Charsets.UTF_8)
        val format = Format.Builder().setSampleMimeType(mime(file.name)).build()
        val parser = DefaultSubtitleParserFactory().create(format)
        val entries = mutableListOf<TimedCues>()
        parser.parse(normalized, SubtitleParser.OutputOptions.allCues()) { block ->
            entries.add(TimedCues(block.startTimeUs / 1000, block.durationUs / 1000, block.cues.toList()))
        }
        require(entries.isNotEmpty()) { "Субтитры пусты или формат файла повреждён" }
        SubtitleTimeline(entries)
    }
}
