package com.webdav.music.webdav

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 管理器：按 trustSelfSigned 缓存实例，
 * 复用连接池和 SSL 配置，避免每次请求新建连接（握手开销大）。
 *
 * - WebDavClient（扫描/歌词下载）直接使用 [forWebDav]
 * - WebDavDataSourceFactory（流式播放）基于 [forWebDav] 用 newBuilder 派生
 * - OnlineMeta 已有独立的 lazy 单例（超时短，适合公网 API）
 */
object OkHttpHolder {

    @Volatile private var webDav: OkHttpClient? = null
    @Volatile private var webDavTrust: Boolean? = null

    /**
     * WebDAV 操作共享客户端：15s 连接超时，30s 读取超时。
     * Authorization 由调用方在 Request header 中添加（非拦截器）。
     */
    fun forWebDav(trustSelfSigned: Boolean): OkHttpClient {
        val existing = webDav
        if (existing != null && webDavTrust == trustSelfSigned) return existing
        synchronized(this) {
            if (webDav != null && webDavTrust == trustSelfSigned) {
                return webDav!!
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            SslHelper.applySsl(builder, trustSelfSigned)
            val client = builder.build()
            webDav = client
            webDavTrust = trustSelfSigned
            return client
        }
    }
}
