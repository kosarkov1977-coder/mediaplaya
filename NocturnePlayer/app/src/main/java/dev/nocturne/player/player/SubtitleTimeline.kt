package dev.nocturne.player.player

import androidx.media3.common.text.Cue

data class TimedCues(val startMs: Long, val durationMs: Long, val cues: List<Cue>)

/** Prefix maximum ends allow backward scans to stop even with overlapping ASS dialogues. */
class SubtitleTimeline(entries: List<TimedCues>) {
    private val sorted = entries.sortedBy { it.startMs }
    private val maxEnd = LongArray(sorted.size)
    init { sorted.forEachIndexed { i, c -> maxEnd[i] = maxOf(if (i > 0) maxEnd[i - 1] else Long.MIN_VALUE, c.startMs + c.durationMs) } }
    fun at(positionMs: Long, delayMs: Long): List<Cue> {
        val time = positionMs - delayMs
        var low = 0; var high = sorted.size
        while (low < high) { val mid = (low + high) ushr 1; if (sorted[mid].startMs <= time) low = mid + 1 else high = mid }
        var i = low - 1
        val result = mutableListOf<Cue>()
        while (i >= 0 && maxEnd[i] > time) {
            val block = sorted[i--]
            if (block.startMs + block.durationMs > time) result.addAll(0, block.cues)
        }
        return result.distinct()
    }
}
