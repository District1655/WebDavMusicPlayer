package com.webdav.music.playback

import android.content.Context
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.webdav.music.data.WebDavPrefs
import java.io.File

/**
 * 歌曲缓存（边听边存）：
 *  - SimpleCache 全局单例（Media3 要求进程内唯一），LRU 淘汰，上限由用户设置
 *  - 上限变化时 get() 自动 release 旧实例并按新上限重建
 *  - 不传 DatabaseProvider：索引仅内存态（冷启动自动扫描重建），规避 Android 10 上 SQLite/Room 相关崩溃
 */
@Suppress("DEPRECATION")
object MediaCache {

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var builtForBytes = -1L

    private fun cacheDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "audio_cache")

    /** 缓存上限（字节） */
    private fun maxBytes(ctx: Context): Long =
        WebDavPrefs.getCacheMaxMb(ctx).toLong() * 1024L * 1024L

    /** 获取全局 SimpleCache 实例；上限与现有实例不符时自动重建；构造失败时清目录重试一次 */
    @Synchronized
    fun get(ctx: Context): SimpleCache {
        val want = maxBytes(ctx)
        val cur = cache
        if (cur != null && builtForBytes == want) return cur
        if (cur != null) {
            try {
                cur.release()
            } catch (_: Exception) {
            }
        }
        val dir = cacheDir(ctx).apply { mkdirs() }
        cache = try {
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(want))
        } catch (first: Exception) {
            // 同目录残留实例冲突等异常：清目录后重试一次
            try {
                dir.listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {
            }
            try {
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(want))
            } catch (second: Exception) {
                throw RuntimeException("SimpleCache init failed", second).initCause(first)
            }
        }
        builtForBytes = want
        return cache!!
    }

    /** 已用空间（字节），需在 IO 线程调用 */
    fun usedBytes(ctx: Context): Long =
        try {
            cacheDir(ctx).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }

    /** 清空全部缓存文件，需在 IO 线程调用 */
    fun clear(ctx: Context) {
        val c = cache
        if (c != null) {
            try {
                c.keys.forEach { key ->
                    try {
                        c.removeResource(key)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        // 兜底：直接删目录残留文件
        try {
            cacheDir(ctx).listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
    }
}
