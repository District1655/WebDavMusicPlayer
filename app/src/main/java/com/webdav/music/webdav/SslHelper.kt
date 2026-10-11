package com.webdav.music.webdav

import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSL 配置工具：统一 WebDavClient / WebDavDataSourceFactory 的 SSL 行为。
 *
 * 默认使用系统 CA 证书校验（安全）；当 [trustSelfSigned] 为 true 时，
 * 信任所有证书（兼容自建 WebDAV 服务的自签证书场景）。
 */
object SslHelper {

    /** 信任所有证书的 TrustManager（仅用于自签证书场景） */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 跳过主机名校验（仅用于自签证书场景） */
    private val allowAllHosts = HostnameVerifier { _, _ -> true }

    /**
     * 配置 OkHttpClient.Builder 的 SSL。
     * @param trustSelfSigned 是否信任自签证书（跳过校验）
     */
    fun applySsl(builder: okhttp3.OkHttpClient.Builder, trustSelfSigned: Boolean) {
        if (trustSelfSigned) {
            val sslCtx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
            }
            builder.sslSocketFactory(sslCtx.socketFactory, trustAllManager)
            builder.hostnameVerifier(allowAllHosts)
        }
        // trustSelfSigned=false 时不做任何配置，使用 OkHttp 默认的系统 CA 校验
    }
}
