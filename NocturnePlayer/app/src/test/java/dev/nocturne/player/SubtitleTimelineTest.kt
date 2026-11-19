package dev.nocturne.player

import androidx.media3.common.text.Cue
import dev.nocturne.player.player.SubtitleTimeline
import dev.nocturne.player.player.TimedCues
import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    private fun cue(text: String) = Cue.Builder().setText(text).build()
    @Test fun boundariesAreHalfOpenAndDelayIsSigned() {
        val a = cue("A"); val timeline = SubtitleTimeline(listOf(TimedCues(1000, 1000, listOf(a))))
        assertTrue(timeline.at(999, 0).isEmpty())
        assertEquals(listOf(a), timeline.at(1000, 0))
        assertTrue(timeline.at(2000, 0).isEmpty())
        assertTrue(timeline.at(1000, 100).isEmpty())
        assertEquals(listOf(a), timeline.at(1100, 100))
        assertEquals(listOf(a), timeline.at(900, -100))
    }
    @Test fun overlappingAssDialogueAndBackwardSeek() {
        val a = cue("Long dialogue"); val b = cue("Sign")
        val timeline = SubtitleTimeline(listOf(TimedCues(2000, 500, listOf(b)), TimedCues(0, 5000, listOf(a))))
        assertEquals(listOf(a, b), timeline.at(2200, 0))
        assertEquals(listOf(a), timeline.at(2600, 0))
        assertEquals(listOf(a), timeline.at(100, 0))
        assertTrue(timeline.at(5000, 0).isEmpty())
    }
    @Test fun emptyTrackIsSafe() { assertTrue(SubtitleTimeline(emptyList()).at(0, 0).isEmpty()) }
}
