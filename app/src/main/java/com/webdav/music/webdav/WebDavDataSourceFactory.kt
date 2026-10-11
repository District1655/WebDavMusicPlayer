package com.webdav.music.webdav

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.webdav.music.data.WebDavPrefs
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WebDAV 流式播放 DataSource 工厂：
 *  - 复用 WebDavPrefs 配置（baseUrl/user/pass）
 *  - OkHttpClient Basic Auth 拦截器 + 可选自签证书信任
 *  - OkHttpDataSource 天然支持 Range 请求，ExoPlayer 拖动/缓冲按需加载
 *
 * 用法：PlaybackService 用 create(context) 拿到 DataSource.Factory，
 * 再用 DefaultMediaSourceFactory(ctx).setDataSourceFactory(...) 喂给 ExoPlayer。
 */
object WebDavDataSourceFactory {

    /**
     * 构造带 Basic Auth 的 OkHttpClient：基于 OkHttpHolder 共享实例派生，
     * 复用连接池和 SSL 配置，仅定制播放专用超时和 Auth 拦截器。
     * @param trustSelfSigned 是否信任自签证书（跳过 SSL 校验）
     */
    fun buildOkHttp(user: String, pass: String, trustSelfSigned: Boolean = false): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Authorization", Credentials.basic(user, pass))
                .build()
            chain.proceed(req)
        }
        return OkHttpHolder.forWebDav(trustSelfSigned).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 不限制单次 call 总时长（流式播放可能很久）
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .build()
    }

    /**
     * 构造 DataSource.Factory：
     *  - 上游 = OkHttpDataSource.Factory（带 Basic Auth，主负责 HTTP Range 请求）
     *  - 包装层 = DefaultDataSource.Factory（让 file/content 等本地 scheme 走默认，但本应用不会用到）
     */
    fun create(context: Context, user: String, pass: String,
               trustSelfSigned: Boolean = false): DataSource.Factory {
        val okHttp = buildOkHttp(user, pass, trustSelfSigned)
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent("WebDavMusicPlayer/1.0")
        return DefaultDataSource.Factory(context, httpFactory)
    }

    /** 便捷重载：从 SharedPreferences 读配置 */
    fun createFromPrefs(context: Context): DataSource.Factory {
        val user = WebDavPrefs.getUser(context)
        val pass = WebDavPrefs.getPass(context)
        val trust = WebDavPrefs.isTrustSelfSigned(context)
        return create(context, user, pass, trust)
    }
}
