package com.webdav.music.webdav

import android.os.SystemClock
import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 客户端：PROPFIND 列目录 + 递归扫描 + 文本下载（歌词）
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val trustSelfSigned: Boolean = false
) {
    companion object {
        private const val TAG = "WebDavScan"

        /** 支持的音乐扩展名 */
        val MUSIC_EXTS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma")
        /** 扫描时额外收集的伴生文件（歌词） */
        val SCAN_EXTS = MUSIC_EXTS + setOf("lrc")
    }

    private val client: OkHttpClient = OkHttpHolder.forWebDav(trustSelfSigned)

    /** PROPFIND 列目录，流式解析响应，返回符合条件的子项（不含目录自身） */
    fun listDirectory(
        remotePath: String = "/",
        depth: String = "1",
        include: (WebDavEntry) -> Boolean = { true }
    ): List<WebDavEntry> {
        var url = baseUrl.trimEnd('/') + "/" + remotePath.trimStart('/').trimEnd('/')
        if (!url.endsWith("/")) url += "/"

        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
            |<propfind xmlns="DAV:">
            |<prop>
            |<displayname/>
            |<getcontentlength/>
            |<getcontenttype/>
            |<resourcetype/>
            |<getlastmodified/>
            |</prop>
            |</propfind>""".trimMargin()

        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .addHeader("Depth", depth)
            .addHeader("Authorization", Credentials.basic(username, password))
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code != 207) {
                throw RuntimeException("PROPFIND 失败: HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body ?: throw RuntimeException("响应体为空")
            return body.byteStream().use { input -> parseMultiStatus(input, include) }
        }
    }

    /**
     * 扫描全部音乐文件（含同名 .lrc 歌词）：优先用 Depth:infinity 一次拉全树（0.1s），
     * 服务端不支持时 fallback 到并发递归扫描。
     * 返回音频 + lrc 的全部条目，由调用方按扩展名配对。
     * @param onProgress (已发现音频数) 回调，解析过程中触发
     */
    suspend fun scanMusic(
        remotePath: String = "/",
        onProgress: ((count: Int) -> Unit)? = null
    ): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        fun extOf(e: WebDavEntry) = e.name.substringAfterLast('.', "").lowercase()
        fun elapsedMs() = SystemClock.elapsedRealtime() - startedAt

        // 方案1：Depth:infinity 一次拉全树，并在流式解析时直接过滤无关条目。
        try {
            val files = listDirectory(remotePath, depth = "infinity") {
                !it.isDirectory && extOf(it) in SCAN_EXTS
            }
            val musicCount = files.count { extOf(it) in MUSIC_EXTS }
            onProgress?.invoke(musicCount)
            Log.d(TAG, "scan complete mode=infinity directories=1 entries=${files.size} " +
                "music=$musicCount elapsedMs=${elapsedMs()}")
            return@withContext files
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // infinity 失败仅用于触发递归回退；回退后的任何目录失败都会终止扫描。
            Log.w(TAG, "Depth infinity failed, fallback to recursive scan elapsedMs=${elapsedMs()}", e)
        }

        // 方案2：并发递归扫描 fallback。任何目录失败均抛出，调用方不得用部分结果清库。
        val result = java.util.Collections.synchronizedList(mutableListOf<WebDavEntry>())
        val musicCount = java.util.concurrent.atomic.AtomicInteger(0)
        val completedDirs = java.util.concurrent.atomic.AtomicInteger(0)
        val queue = java.util.concurrent.ConcurrentLinkedQueue<String>()
        queue.add(remotePath)
        val activeCount = java.util.concurrent.atomic.AtomicInteger(1)

        try {
            val jobs = (1..8).map {
                launch {
                    while (activeCount.get() > 0) {
                        val dir = queue.poll()
                        if (dir == null) {
                            delay(50)
                            continue
                        }
                        try {
                            val entries = listDirectory(dir)
                            for (e in entries) {
                                if (e.isDirectory) {
                                    // 先计数再入队，避免其他线程提前消费后把 activeCount 减到 0。
                                    activeCount.incrementAndGet()
                                    queue.add(e.href)
                                } else {
                                    val ext = extOf(e)
                                    if (ext in SCAN_EXTS) {
                                        result.add(e)
                                        if (ext in MUSIC_EXTS) {
                                            onProgress?.invoke(musicCount.incrementAndGet())
                                        }
                                    }
                                }
                            }
                            completedDirs.incrementAndGet()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw WebDavScanException(
                                "目录扫描失败：$dir；已完成 ${completedDirs.get()} 个目录，" +
                                    "已发现 ${musicCount.get()} 首", e
                            )
                        } finally {
                            activeCount.decrementAndGet()
                        }
                    }
                }
            }
            jobs.joinAll()
        } catch (e: Exception) {
            Log.e(TAG, "scan failed mode=recursive directories=${completedDirs.get()} " +
                "music=${musicCount.get()} elapsedMs=${elapsedMs()}", e)
            throw e
        }

        // 所有 worker 已结束，不会再有写入，直接返回以避免再复制一份完整列表。
        Log.d(TAG, "scan complete mode=recursive directories=${completedDirs.get()} " +
            "entries=${result.size} music=${musicCount.get()} elapsedMs=${elapsedMs()}")
        result
    }

    /** 下载文本文件（如 .lrc 歌词），失败返回 null */
    fun fetchText(remotePath: String): String? {
        val url = baseUrl.trimEnd('/') + "/" + remotePath.trimStart('/')
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", Credentials.basic(username, password))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 流式解析 207 Multi-Status XML，跳过集合自身和无需保留的条目 */
    private fun parseMultiStatus(
        input: InputStream,
        include: (WebDavEntry) -> Boolean
    ): List<WebDavEntry> {
        val result = mutableListOf<WebDavEntry>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        var href: String? = null
        var isCollection = false
        var displayName: String? = null
        var size: Long = 0L

        fun localName(name: String): String = name.substringAfter(':')

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "href" -> { href = parser.nextText() }
                    "displayname" -> { displayName = parser.nextText() }
                    "collection" -> { isCollection = true }
                    "getcontentlength" -> { size = parser.nextText().toLongOrNull() ?: 0L }
                }
                XmlPullParser.END_TAG -> {
                    if (localName(parser.name) == "response" && href != null) {
                        val isSelf = href == "/" || href.isNullOrEmpty()
                        if (!isSelf) {
                            val entry = WebDavEntry(
                                href = href!!,
                                name = displayName?.takeIf { it.isNotEmpty() }
                                    ?: decodeHrefName(href!!),
                                isDirectory = isCollection,
                                size = size
                            )
                            if (include(entry)) result.add(entry)
                        }
                        href = null
                        isCollection = false
                        displayName = null
                        size = 0L
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun decodeHrefName(href: String): String {
        val decoded = URLDecoder.decode(href, "UTF-8")
        return decoded.trimEnd('/').substringAfterLast('/')
    }
}

/** WebDAV 条目 */
data class WebDavEntry(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

/** 扫描未完整完成；调用方必须保留原曲库，不得提交部分结果。 */
class WebDavScanException(message: String, cause: Throwable) : RuntimeException(message, cause)
