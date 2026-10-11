package com.webdav.music.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
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
        /** 支持的音乐扩展名 */
        val MUSIC_EXTS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma")
        /** 扫描时额外收集的伴生文件（歌词） */
        val SCAN_EXTS = MUSIC_EXTS + setOf("lrc")
    }

    private val client: OkHttpClient = OkHttpHolder.forWebDav(trustSelfSigned)

    /** PROPFIND 列目录，返回子项（不含目录自身） */
    fun listDirectory(remotePath: String = "/", depth: String = "1"): List<WebDavEntry> {
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
            val body = resp.body?.string() ?: throw RuntimeException("响应体为空")
            return parseMultiStatus(body)
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
        fun extOf(e: WebDavEntry) = e.name.substringAfterLast('.', "").lowercase()

        // 方案1：Depth:infinity 一次拉全树（nginx 支持时最快）
        try {
            val entries = listDirectory(remotePath, depth = "infinity")
            val files = entries.filter { !it.isDirectory && extOf(it) in SCAN_EXTS }
            onProgress?.invoke(files.count { extOf(it) in MUSIC_EXTS })
            return@withContext files
        } catch (e: Exception) {
            // infinity 不支持，走递归
        }

        // 方案2：并发递归扫描 fallback
        val result = java.util.Collections.synchronizedList(mutableListOf<WebDavEntry>())
        val musicCount = java.util.concurrent.atomic.AtomicInteger(0)
        val queue = java.util.concurrent.ConcurrentLinkedQueue<String>()
        queue.add(remotePath)
        val activeCount = java.util.concurrent.atomic.AtomicInteger(1)

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
                        var newDirs = 0
                        for (e in entries) {
                            if (e.isDirectory) {
                                queue.add(e.href)
                                newDirs++
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
                        activeCount.addAndGet(newDirs)
                        activeCount.decrementAndGet()
                    } catch (e: Exception) {
                        activeCount.decrementAndGet()
                    }
                }
            }
        }
        jobs.joinAll()
        result.toList()
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

    /** 解析 207 Multi-Status XML，跳过集合自身 */
    private fun parseMultiStatus(xml: String): List<WebDavEntry> {
        val result = mutableListOf<WebDavEntry>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

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
                            result.add(WebDavEntry(
                                href = href!!,
                                name = displayName?.takeIf { it.isNotEmpty() }
                                    ?: decodeHrefName(href!!),
                                isDirectory = isCollection,
                                size = size
                            ))
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
