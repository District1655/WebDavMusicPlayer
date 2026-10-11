package com.webdav.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricUtilsTest {

    // ---------- parseLrc ----------

    @Test
    fun parseLrc_standardTimestamps() {
        val lrc = """
            [00:01.00]第一行歌词
            [00:03.50]第二行歌词
            [01:10.00]第三行歌词
        """.trimIndent()

        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(1000L, lines[0].lineStartMs)
        assertEquals("第一行歌词", lines[0].text)
        assertEquals(3500L, lines[1].lineStartMs)
        assertEquals("第二行歌词", lines[1].text)
        assertEquals(70000L, lines[2].lineStartMs)
        assertEquals("第三行歌词", lines[2].text)
    }

    @Test
    fun parseLrc_multipleTimestampsSameLine() {
        val lrc = "[00:01.00][00:05.00]重复歌词"
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].lineStartMs)
        assertEquals(5000L, lines[1].lineStartMs)
        assertEquals("重复歌词", lines[0].text)
        assertEquals("重复歌词", lines[1].text)
    }

    @Test
    fun parseLrc_wordLevelTimestamps() {
        val lrc = "[00:01.00]<00:01.00>你<00:01.50>好<00:02.00>世<00:02.50>界"
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(1, lines.size)
        assertEquals(1000L, lines[0].lineStartMs)
        assertEquals("你好世界", lines[0].text)
        assertEquals(4, lines[0].words.size)
        assertEquals(1000L, lines[0].words[0].startMs)
        assertEquals("你", lines[0].words[0].text)
        assertEquals(1500L, lines[0].words[1].startMs)
        assertEquals("好", lines[0].words[1].text)
    }

    @Test
    fun parseLrc_plainTextNoTimestamps() {
        val lrc = "第一行\n第二行\n第三行"
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].lineStartMs)
        assertEquals(8000L, lines[1].lineStartMs)
        assertEquals(16000L, lines[2].lineStartMs)
        assertTrue(lines[0].words.isEmpty())
    }

    @Test
    fun parseLrc_emptyText() {
        val lines = LyricUtils.parseLrc("")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun parseLrc_linesSortedByTime() {
        val lrc = """
            [00:05.00]B
            [00:01.00]A
            [00:03.00]C
        """.trimIndent()
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(1000L, lines[0].lineStartMs)
        assertEquals(3000L, lines[1].lineStartMs)
        assertEquals(5000L, lines[2].lineStartMs)
    }

    @Test
    fun parseLrc_fractionVariations() {
        // 1 位、2 位、3 位小数
        val lrc = "[00:01.1]A\n[00:02.12]B\n[00:03.123]C"
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(1100L, lines[0].lineStartMs)  // .1 → 100ms
        assertEquals(2120L, lines[1].lineStartMs)  // .12 → 120ms
        assertEquals(3123L, lines[2].lineStartMs)  // .123 → 123ms
    }

    @Test
    fun parseLrc_skipsEmptyContentLines() {
        val lrc = "[00:01.00]\n[00:02.00]有效歌词"
        val lines = LyricUtils.parseLrc(lrc)
        assertEquals(1, lines.size)
        assertEquals("有效歌词", lines[0].text)
    }

    // ---------- displayTitle ----------

    @Test
    fun displayTitle_artistSongFormat() {
        assertEquals("雨一直下 — 张宇", LyricUtils.displayTitle("张宇 - 雨一直下.mp3"))
    }

    @Test
    fun displayTitle_noExtension() {
        assertEquals("雨一直下 — 张宇", LyricUtils.displayTitle("张宇 - 雨一直下"))
    }

    @Test
    fun displayTitle_noSeparator() {
        assertEquals("纯歌名", LyricUtils.displayTitle("纯歌名.mp3"))
    }

    @Test
    fun displayTitle_emptyArtist() {
        // trim 去掉前导空格后无 " - " 分隔符 → 原样返回（去扩展名）
        assertEquals("- 纯歌名", LyricUtils.displayTitle(" - 纯歌名.mp3"))
    }

    @Test
    fun displayTitle_emptySong() {
        assertEquals("张宇", LyricUtils.displayTitle("张宇 - .mp3"))
    }

    @Test
    fun displayTitle_trimsWhitespace() {
        assertEquals("歌 — 手", LyricUtils.displayTitle("  手 - 歌.mp3  "))
    }

    // ---------- songTitleArtist ----------

    @Test
    fun songTitleArtist_normal() {
        val (artist, title) = LyricUtils.songTitleArtist("张宇 - 雨一直下.mp3")
        assertEquals("张宇", artist)
        assertEquals("雨一直下", title)
    }

    @Test
    fun songTitleArtist_noSeparator() {
        val (artist, title) = LyricUtils.songTitleArtist("纯歌名.mp3")
        assertEquals("", artist)
        assertEquals("纯歌名", title)
    }

    @Test
    fun songTitleArtist_noExtension() {
        val (artist, title) = LyricUtils.songTitleArtist("张宇 - 雨一直下")
        assertEquals("张宇", artist)
        assertEquals("雨一直下", title)
    }

    // ---------- parseArtistName ----------

    @Test
    fun parseArtistName_normal() {
        assertEquals("张宇", LyricUtils.parseArtistName("张宇 - 雨一直下.mp3"))
    }

    @Test
    fun parseArtistName_noSeparator() {
        assertEquals("", LyricUtils.parseArtistName("纯歌名.mp3"))
    }

    // ---------- formatTime ----------

    @Test
    fun formatTime_zero() {
        assertEquals("00:00", LyricUtils.formatTime(0))
    }

    @Test
    fun formatTime_normal() {
        assertEquals("01:30", LyricUtils.formatTime(90000))
    }

    @Test
    fun formatTime_negative() {
        assertEquals("--:--", LyricUtils.formatTime(-1))
    }

    @Test
    fun formatTime_longDuration() {
        assertEquals("10:00", LyricUtils.formatTime(600000))
    }

    // ---------- lrcKey ----------

    @Test
    fun lrcKey_decodesAndNormalizes() {
        val key = LyricUtils.lrcKey("/music/%E5%BC%A0%E5%AE%87/%E9%9B%A8.mp3")
        assertTrue(key.contains("张宇"))
        assertTrue(key.contains("雨"))
        assertTrue(key.endsWith(".mp3".substringBeforeLast('.').lowercase()))
    }

    @Test
    fun lrcKey_stripsExtension() {
        val key = LyricUtils.lrcKey("/music/song.mp3")
        assertEquals("/music/song", key)
    }

    @Test
    fun lrcKey_trimsTrailingSlash() {
        val key = LyricUtils.lrcKey("/music/song.mp3/")
        assertEquals("/music/song", key)
    }

    // ---------- md5Key ----------

    @Test
    fun md5Key_deterministic() {
        val key1 = LyricUtils.md5Key("/music/song.mp3")
        val key2 = LyricUtils.md5Key("/music/song.mp3")
        assertEquals(key1, key2)
    }

    @Test
    fun md5Key_differentInputs() {
        val key1 = LyricUtils.md5Key("/music/song1.mp3")
        val key2 = LyricUtils.md5Key("/music/song2.mp3")
        assert(key1 != key2)
    }

    @Test
    fun md5Key_is32CharHex() {
        val key = LyricUtils.md5Key("test")
        assertEquals(32, key.length)
        assertTrue(key.matches(Regex("^[0-9a-f]{32}$")))
    }
}
