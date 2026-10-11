package com.webdav.music.webdav

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 在线歌曲元信息（封面字节 + LRC 歌词文本）：歌词多源级联，封面网易云+iTunes 兜底 */
object OnlineMeta {

    data class Meta(val coverBytes: ByteArray?, val lrcText: String?)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

    private fun get(url: String): String? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com")
                .build()
        ).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }

    private fun postForm(url: String, form: Map<String, String>): String? = try {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(
            Request.Builder().url(url).post(body)
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com")
                .build()
        ).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }

    private fun getBytes(url: String): ByteArray? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .build()
        ).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) { null }

    /** 从搜索响应提取 (songId, picUrl)，兼容 songs / result.songs 与 album / al 两种格式 */
    private fun parseSearch(body: String): Pair<Long, String?>? = runCatching {
        val obj = JSONObject(body)
        val arr = obj.optJSONArray("songs")
            ?: obj.optJSONObject("result")?.optJSONArray("songs")
            ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val song = arr.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            val album = song.optJSONObject("album") ?: song.optJSONObject("al")
            val pic = album?.optString("picUrl")
            if (id > 0 && !pic.isNullOrEmpty()) return@runCatching id to pic
            if (id > 0 && i == arr.length() - 1) return@runCatching id to null
        }
        null
    }.getOrNull()

    /** "张宇 - 雨一直下.mp3" → (歌手, 歌名)；无分隔符返回 ("", 文件名) */
    private fun parsePair(query: String): Pair<String, String> {
        var name = query.trim()
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.length - dot <= 5) name = name.substring(0, dot)
        val idx = name.indexOf(" - ")
        return if (idx > 0) {
            name.substring(0, idx).trim() to name.substring(idx + 3).trim()
        } else "" to name
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** QQ 音乐搜索：返回 (songmid, albummid) */
    private fun qqSearch(artist: String, title: String): Pair<String, String>? {
        val kw = if (artist.isNotEmpty()) "$artist $title" else title
        val body = get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(kw)}&format=json&p=1&n=3")
            ?: return null
        return runCatching {
            val list = JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                ?: return@runCatching null
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val mid = s.optString("songmid")
                val alb = s.optString("albummid")
                if (mid.isNotEmpty() && alb.isNotEmpty()) return@runCatching mid to alb
            }
            null
        }.getOrNull()
    }

    /** QQ 音乐歌词：需 Referer */
    private fun qqLyric(songmid: String): String? {
        if (songmid.isEmpty()) return null
        return try {
            http.newCall(
                Request.Builder()
                    .url("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&format=json&nobase64=1")
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                runCatching { JSONObject(body).optString("lyric") }.getOrNull()
            }
        } catch (_: Exception) { null }
    }

    /** 酷狗搜索：返回 (hash, album_audio_id) */
    private fun kugouSearch(artist: String, title: String): Pair<String, String>? {
        val kw = if (artist.isNotEmpty()) "$artist $title" else title
        // mobilecdn.kugou.com 不支持 HTTPS，保留 HTTP
        val body = get("http://mobilecdn.kugou.com/api/v3/search/song?keyword=${enc(kw)}&page=1&pagesize=3")
            ?: return null
        return runCatching {
            val list = JSONObject(body).optJSONObject("data")?.optJSONArray("info")
                ?: return@runCatching null
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val hash = s.optString("hash")
                val aid = s.optString("album_audio_id")
                if (hash.isNotEmpty() && aid.isNotEmpty()) return@runCatching hash to aid
                if (hash.isNotEmpty() && i == list.length() - 1) return@runCatching hash to ""
            }
            null
        }.getOrNull()
    }

    /** 酷狗歌词：先搜索 candidates，再下载 LRC */
    private fun kugouLyric(hash: String, albumAudioId: String): String? {
        if (hash.isEmpty()) return null
        val search = get("https://krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=&duration=0&hash=$hash&album_audio_id=$albumAudioId&lrctxt=1")
            ?: return null
        val (id, key) = runCatching {
            val arr = JSONObject(search).optJSONArray("candidates") ?: return@runCatching "" to ""
            if (arr.length() == 0) return@runCatching "" to ""
            val c = arr.optJSONObject(0)
            c.optString("id") to c.optString("accesskey")
        }.getOrNull() ?: ("" to "")
        if (id.isEmpty()) return null
        val dl = get("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=lrc&charset=utf8")
            ?: return null
        return runCatching { JSONObject(dl).optString("content") }.getOrNull()
    }

    /**
     * 歌词多源级联（免费接口，按可用性排序）：
     * 1. lrclib.net/api/get（同步歌词 syncedLyrics，失败补 plainLyrics）
     * 2. api.lrc.cx/lyrics（纯 LRC 文本）
     * 3. QQ 音乐歌词（带时间轴 LRC）
     * 4. 酷狗音乐歌词（带时间轴 LRC）
     * 5. api.lyrics.ovh/v1（纯文本歌词，无时间轴）
     * 6. 网易云 api/song/lyric?id=（复用封面搜索拿到的歌曲 id）
     */
    private fun fetchLrc(artist: String, title: String, durationSec: Long, neteaseId: Long,
                         qq: Pair<String, String>? = null, kg: Pair<String, String>? = null): String? {
        if (title.isEmpty()) return null
        val tE = enc(title)

        // 1) LrcLib：歌手+歌名（正反两种切分都试，兼容"歌名 - 歌手"文件名）
        val artistPairs = if (artist.isNotEmpty()) listOf(artist, "") else listOf("")
        for (a in artistPairs) {
            val url = buildString {
                append("https://lrclib.net/api/get?track_name=").append(tE)
                if (a.isNotEmpty()) append("&artist_name=").append(enc(a))
                if (durationSec > 0) append("&duration=").append(durationSec)
            }
            val body = get(url)
            if (!body.isNullOrEmpty() && body.contains("\"syncedLyrics\"")) {
                val lrc = runCatching {
                    val o = JSONObject(body)
                    o.optString("syncedLyrics", "").takeIf { it.contains("[") && it.isNotBlank() }
                        ?: o.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                }.getOrNull()
                if (!lrc.isNullOrEmpty()) return lrc
            }
        }

        // 2) api.lrc.cx
        if (artist.isNotEmpty()) {
            get("https://api.lrc.cx/lyrics?title=$tE&artist=${enc(artist)}")
                ?.takeIf { it.isNotBlank() }?.let { return it }
            get("https://api.lrc.cx/lyrics?title=$tE")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 3) QQ 音乐歌词（复用 fetch 中的搜索结果）
        if (qq != null) {
            qqLyric(qq.first)?.takeIf { it.contains("[") }?.let { return it }
        }

        // 4) 酷狗音乐歌词（复用 fetch 中的搜索结果）
        if (kg != null) {
            kugouLyric(kg.first, kg.second)?.takeIf { it.contains("[") }?.let { return it }
        }

        // 5) lyrics.ovh（纯文本）
        if (artist.isNotEmpty()) {
            val b = get("https://api.lyrics.ovh/v1/${enc(artist)}/$tE")
            val ly = b?.let { runCatching { JSONObject(it).optString("lyrics") }.getOrNull() }
            if (!ly.isNullOrBlank()) return ly
        }

        // 6) 网易云歌词（id 来自封面搜索）
        if (neteaseId > 0) {
            val ly = get("https://music.163.com/api/song/lyric?id=$neteaseId&lv=1&kv=1&tv=-1")
                ?: get("https://music.163.com/api/song/lyric?id=$neteaseId&lv=1&kv=1&tv=-1")
            val lrc = ly?.let { body -> runCatching {
                JSONObject(body).optJSONObject("lrc")?.optString("lyric")
            }.getOrNull() }
            if (!lrc.isNullOrEmpty()) return lrc
        }
        return null
    }

    /** 搜索并抓取封面/歌词；任一步失败都返回能拿到的部分 */
    fun fetch(query: String, durationSec: Long = 0L): Meta {
        val encAll = enc(query)
        val (artist, title) = parsePair(query)
        var cover: ByteArray? = null

        // 网易云：搜索（GET http/https 双通道 → POST 兜底，防单接口失效/跳转）
        var songId = 0L
        var picUrl: String? = null
        val bodies = listOf(
            get("https://music.163.com/api/search/get/web?s=$encAll&type=1&limit=3"),
            get("https://music.163.com/api/search/get/web?s=$encAll&type=1&limit=3"),
            postForm("https://music.163.com/api/search/get",
                mapOf("s" to query, "type" to "1", "limit" to "3", "offset" to "0"))
        )
        for (b in bodies) {
            if (b.isNullOrEmpty() || !b.contains("\"songs\"")) continue
            val parsed = parseSearch(b) ?: continue
            songId = parsed.first
            picUrl = parsed.second
            break
        }
        // 网易云封面下载
        if (!picUrl.isNullOrEmpty()) cover = getBytes(picUrl)

        // QQ 音乐搜索（复用给歌词+封面）
        val qq = qqSearch(artist, title)
        // 酷狗音乐搜索（复用给歌词+封面）
        val kg = kugouSearch(artist, title)

        // 歌词：多源级联（传入 qq/kg 搜索结果避免重复请求）
        val lrc = fetchLrc(artist, title, durationSec, songId, qq, kg)

        // QQ 音乐封面兜底
        if (cover == null && qq != null) {
            cover = getBytes("https://y.qq.com/music/photo_new/T002R300x300M000${qq.second}.jpg")
        }
        // 酷狗音乐封面兜底
        if (cover == null && kg != null) {
            cover = getBytes("https://singerimg.kugou.com/uploadpic/softhead/400/${kg.first}.jpg")
        }
        // iTunes 封面兜底
        if (cover == null) {
            val itunes = get("https://itunes.apple.com/search?term=$encAll&entity=song&limit=1")
            val pic = itunes?.let { t -> runCatching {
                JSONObject(t).optJSONArray("results")?.optJSONObject(0)
                    ?.optString("artworkUrl100")
            }.getOrNull() }
            if (!pic.isNullOrEmpty()) {
                cover = getBytes(pic.replace("100x100", "600x600"))
            }
        }
        return Meta(cover, lrc?.takeIf { it.isNotBlank() })
    }
}
