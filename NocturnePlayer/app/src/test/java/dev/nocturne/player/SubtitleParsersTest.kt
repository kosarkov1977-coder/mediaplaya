@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.room.Room
import dev.nocturne.player.data.*
import dev.nocturne.player.player.SubtitleTimeline
import dev.nocturne.player.player.TimedCues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubtitleParsersTest {
    private val fixtures = mapOf(
        "srt" to "1\n00:00:01,000 --> 00:00:03,000\nПривет\n\n",
        "vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nПривет\n\n",
        "ass" to """[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,36,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(640,600)}Привет
""",
        "ssa" to """[Script Info]
ScriptType: v4.00
[Events]
Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: Marked=0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Привет
""",
        "ttml" to """<?xml version="1.0" encoding="UTF-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01.000" end="00:00:03.000">Привет</p></div></body></tt>"""
    )
    @Test fun allFiveFormatsParseAndSupportLiveDelay() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).allowMainThreadQueries().build()
        try { fixtures.forEach { (extension, source) ->
            val file = File(context.cacheDir, "test.$extension").apply { writeText(source) }
            val timeline = SubtitleRepository(context, database.dao()).load(SubtitleFile("video", Uri.fromFile(file).toString(), file.name))
            assertTrue("$extension must be invisible before start", timeline.at(999, 0).isEmpty())
            assertTrue("$extension must render Cyrillic", timeline.at(1500, 0).any { it.text.toString().contains("Привет") })
            assertTrue("$extension positive delay", timeline.at(1000, 100).isEmpty())
            assertTrue("$extension negative delay", timeline.at(900, -100).isNotEmpty())
            assertTrue("$extension must end", timeline.at(3000, 0).isEmpty())
            if (extension == "ass") {
                assertEquals("ASS horizontal position is retained", .5f, timeline.at(1500, 0).first().position, .001f)
                assertEquals("ASS vertical position is retained", 600f / 720, timeline.at(1500, 0).first().line, .001f)
            }
            file.delete()
        } } finally { database.close() }
    }
    @Test fun repositoryReadsWindows1251AndUtf16WithoutLosingText() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = SubtitleRepository(context, database.dao())
            for (encoding in listOf("windows-1251", "UTF-16LE")) {
                val file = File(context.cacheDir, "encoding-$encoding.srt")
                val bytes = fixtures.getValue("srt").toByteArray(charset(encoding))
                file.writeBytes(if (encoding == "UTF-16LE") byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes else bytes)
                val timeline = repository.load(SubtitleFile("video", Uri.fromFile(file).toString(), file.name))
                assertTrue(timeline.at(1500, 0).any { it.text.toString() == "Привет" })
                file.delete()
            }
        } finally { database.close() }
    }
}
