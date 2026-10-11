package com.webdav.music.util

/**
 * 歌词与标题处理的纯函数集合，无 Android 依赖，便于单元测试。
 */
object LyricUtils {

    /** 歌词数据结构：逐行歌词 + 可选逐字时间戳 */
    data class LyricWord(val startMs: Long, val text: String)
    data class LyricLine(val lineStartMs: Long, val text: String, val words: List<LyricWord> = emptyList())

    private val lrcTimeRegex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    // 逐字时间戳：<mm:ss.xx> 增强型 LRC
    private val lrcWordRegex = Regex("<(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")

    /** 路径 URL 解码 + 去扩展名 + 小写，用于音频/歌词配对 */
    fun lrcKey(href: String): String = runCatching {
        java.net.URLDecoder.decode(href, "UTF-8")
    }.getOrDefault(href).trimEnd('/').substringBeforeLast('.').lowercase()

    /** 解析 .lrc 文本 → LyricLine 有序列表，支持增强型逐字歌词 <mm:ss.xx> */
    fun parseLrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        text.lines().forEach { line ->
            // 1. 提取行时间戳 [mm:ss.xx]
            val lineTimes = mutableListOf<Long>()
            var lastEnd = 0
            for (m in lrcTimeRegex.findAll(line)) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when {
                    fracStr.isEmpty() -> 0L
                    fracStr.length == 1 -> fracStr.toLong() * 100
                    fracStr.length == 2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                lineTimes.add(min * 60000 + sec * 1000 + frac)
                lastEnd = m.range.last + 1
            }
            if (lineTimes.isEmpty()) return@forEach
            val content = line.substring(lastEnd).trim()
            if (content.isEmpty()) return@forEach

            // 2. 解析逐字时间戳 <mm:ss.xx>
            val wordMatches = lrcWordRegex.findAll(content).toList()
            val words = mutableListOf<LyricWord>()
            if (wordMatches.isNotEmpty()) {
                // 第一个字的起始时间 = 行时间（紧跟 [mm:ss] 后的文字）
                val firstText = content.substring(0, wordMatches[0].range.first).trim()
                if (firstText.isNotEmpty()) words.add(LyricWord(lineTimes[0], firstText))
                // 后续每个 <mm:ss.xx> 标签对应其后文字的起始时间
                for (i in wordMatches.indices) {
                    val m = wordMatches[i]
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val fracStr = m.groupValues[3]
                    val frac = when {
                        fracStr.isEmpty() -> 0L
                        fracStr.length == 1 -> fracStr.toLong() * 100
                        fracStr.length == 2 -> fracStr.toLong() * 10
                        else -> fracStr.take(3).toLong()
                    }
                    val wordStart = min * 60000 + sec * 1000 + frac
                    val textStart = m.range.last + 1
                    val textEnd = if (i + 1 < wordMatches.size) wordMatches[i + 1].range.first else content.length
                    val wordText = content.substring(textStart, textEnd).trim()
                    if (wordText.isNotEmpty()) words.add(LyricWord(wordStart, wordText))
                }
            }

            // 纯文本（去标签）用于整行显示
            val plainText = lrcWordRegex.replace(content, "")
            lineTimes.forEach { t ->
                out.add(LyricLine(t, plainText, words.toList()))
            }
        }
        out.sortBy { it.lineStartMs }
        if (out.isEmpty()) {
            // 纯文本歌词（无时间轴）：每行按 8 秒近似排布
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, s ->
                out.add(LyricLine(i * 8000L, s))
            }
        }
        return out
    }

    /** "张宇 - 雨一直下.mp3" → "雨一直下 — 张宇"；无分隔符则原样（去扩展名） */
    fun displayTitle(raw: String): String {
        var name = raw.trim()
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.length - dot <= 5) name = name.substring(0, dot)
        val idx = name.indexOf(" - ")
        if (idx <= 0) return name
        val artist = name.substring(0, idx).trim()
        val song = name.substring(idx + 3).trim()
        return if (song.isEmpty()) artist else "$song — $artist"
    }

    /** "张宇 - 雨一直下.mp3" → (歌手, 歌名)；无分隔符时歌手为空 */
    fun songTitleArtist(raw: String): Pair<String, String> {
        var name = raw.trim()
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.length - dot <= 5) name = name.substring(0, dot)
        val idx = name.indexOf(" - ")
        return if (idx <= 0) "" to name
        else name.substring(0, idx).trim() to name.substring(idx + 3).trim()
    }

    /** "张宇 - 雨一直下.mp3" → "张宇"；无分隔符返回空 */
    fun parseArtistName(raw: String): String {
        val name = songTitleArtist(raw).first
        return name
    }

    /** 毫秒 → mm:ss */
    fun formatTime(ms: Long): String {
        if (ms < 0) return "--:--"
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** mediaId → MD5 缓存键 */
    fun md5Key(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
