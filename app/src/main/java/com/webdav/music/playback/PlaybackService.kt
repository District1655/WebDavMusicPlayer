package com.webdav.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.webdav.music.MainActivity
import com.webdav.music.data.TrackStore
import com.webdav.music.data.WebDavPrefs
import com.webdav.music.webdav.WebDavDataSourceFactory

/**
 * 后台播放服务：MediaSessionService + ExoPlayer
 *  - onCreate：构造 ExoPlayer（注入 WebDAV OkHttp DataSource 工厂）
 *              + MediaSession（暴露给系统 MediaButton / 方向盘 / 通知栏）
 *              + 从 TrackStore 加载全部曲目作为初始队列
 *  - onGetSession：返回 MediaSession 给 MediaController
 *
 * 方向盘 keycode 85（KEYCODE_MEDIA_PLAY_PAUSE）走系统 MediaSession 路由，
 * 自动触发 MediaSession 回调 onPlayWhenReadyChanged，无需在此处手动处理。
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /**
     * 可切换的数据源工厂：缓存设置变化时换 delegate，正在播的歌不受影响、下一首生效。
     * 注意：delegate 不能在字段初始化器里构建 —— Service 构造期尚未 attach，
     * getApplicationContext() 为 null 会 NPE（已踩坑）。createDataSource 必然发生在
     * onCreate 之后（首次播放），此处 fallback 调用是安全的。
     */
    private val switchableFactory = object : DataSource.Factory {
        @Volatile private var delegate: DataSource.Factory? = null
        fun setDelegate(f: DataSource.Factory) { delegate = f }
        override fun createDataSource(): DataSource {
            val d = delegate
            return d?.createDataSource()
                ?: WebDavDataSourceFactory.createFromPrefs(this@PlaybackService).createDataSource()
        }
    }

    companion object {
        @Volatile private var instance: PlaybackService? = null

        /** 设置页保存后调用：让缓存开关/上限立即生效（不打断当前播放） */
        fun onCacheConfigChanged() {
            instance?.applyCacheConfig()
        }
    }

    /** 按当前设置重建数据源链；上限变化时重建 SimpleCache。任何异常只降级为直连，不崩进程 */
    private fun applyCacheConfig() {
        Thread {
            try {
                val enabled = WebDavPrefs.isCacheEnabled(this)
                val upstream = WebDavDataSourceFactory.createFromPrefs(this)
                val f: DataSource.Factory = if (enabled) {
                    CacheDataSource.Factory()
                        .setCache(MediaCache.get(this))
                        .setUpstreamDataSourceFactory(upstream)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                } else upstream
                switchableFactory.setDelegate(f)
            } catch (t: Throwable) {
                android.util.Log.e("WebDavMusic", "applyCacheConfig failed", t)
                try {
                    switchableFactory.setDelegate(WebDavDataSourceFactory.createFromPrefs(this))
                } catch (_: Throwable) {
                }
            }
        }.start()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // attach 完成后再构建数据源链（无论缓存开关，统一走 applyCacheConfig 设置 delegate）
        applyCacheConfig()

        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(switchableFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)   // 拔耳机自动暂停
            .setWakeMode(C.WAKE_MODE_LOCAL)      // 播放时持有 PARTIAL_WAKE_LOCK，防止 CPU 休眠
            .build()

        // 启动时加载本地缓存作为初始播放队列（用户首次打开 APP 后即生效）
        val tracks = TrackStore(this).getAll()
        if (tracks.isNotEmpty()) {
            val base = WebDavPrefs.getUrl(this).trimEnd('/')
            val items = tracks.map { t ->
                MediaItem.Builder()
                    .setUri(Uri.parse(base + t.path))
                    .setMediaId(t.path)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.name)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            }
            exo.setMediaItems(items)
        }

        player = exo

        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onDestroy() {
        instance = null
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
