package dev.nocturne.player

import dev.nocturne.player.data.SubtitleRepository
import org.junit.Assert.*
import org.junit.Test

class SubtitleNamesTest {
    @Test fun acceptsExactNameAndLanguageSuffix() {
        assertTrue(SubtitleRepository.matches("Movie.mkv", "Movie.srt"))
        assertTrue(SubtitleRepository.matches("Movie.MP4", "movie.ru.ass"))
        assertTrue(SubtitleRepository.matches("Movie.mp4", "Movie_en.vtt"))
        assertFalse(SubtitleRepository.matches("Movie.mp4", "Movie2.srt"))
        assertFalse(SubtitleRepository.matches("Movie.mp4", "Movie.jpg"))
    }
}
