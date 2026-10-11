package com.webdav.music.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * WebDAV 配置持久化（地址/用户名/密码）
 *
 * 安全策略：
 * - URL、用户名等非敏感字段存储在普通 SharedPreferences
 * - 密码存储在 EncryptedSharedPreferences（Android Keystore 加密）
 */
object WebDavPrefs {

    private const val FILE = "webdav_config"
    private const val SECURE_FILE = "webdav_secure"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
    private const val KEY_LAST_MEDIA_ID = "last_media_id"
    private const val KEY_CACHE_ENABLED = "cache_enabled"
    private const val KEY_CACHE_MAX_MB = "cache_max_mb"
    private const val KEY_AUTO_RESUME = "auto_resume_play"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 加密 SharedPreferences（仅用于密码） */
    private fun securePrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx.applicationContext,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(ctx: Context, url: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(KEY_URL, url)
            .putString(KEY_USER, user)
            .apply()
        securePrefs(ctx).edit()
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun getUrl(ctx: Context): String = prefs(ctx).getString(KEY_URL, "") ?: ""
    fun getUser(ctx: Context): String = prefs(ctx).getString(KEY_USER, "") ?: ""

    fun getPass(ctx: Context): String {
        // 1. 优先从加密存储读取
        val secure = runCatching { securePrefs(ctx).getString(KEY_PASS, "") ?: "" }
            .getOrDefault("")
        if (secure.isNotEmpty()) return secure
        // 2. 数据迁移：旧版密码明文存储在普通 SP，迁移到加密存储后删除明文
        val legacy = prefs(ctx).getString(KEY_PASS, "") ?: ""
        if (legacy.isNotEmpty()) {
            runCatching {
                securePrefs(ctx).edit().putString(KEY_PASS, legacy).apply()
                prefs(ctx).edit().remove(KEY_PASS).apply()
            }
            return legacy
        }
        return ""
    }

    /** 配置是否已填写 */
    fun isConfigured(ctx: Context): Boolean =
        getUrl(ctx).isNotEmpty() && getUser(ctx).isNotEmpty() && getPass(ctx).isNotEmpty()

    /** 是否信任自签 SSL 证书（默认 false，使用系统 CA 校验） */
    fun setTrustSelfSigned(ctx: Context, trust: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TRUST_SELF_SIGNED, trust).apply()
    }

    fun isTrustSelfSigned(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TRUST_SELF_SIGNED, false)

    /** 上次播放的曲目路径（用于下次打开恢复播放位置） */
    fun setLastMediaId(ctx: Context, mediaId: String) {
        prefs(ctx).edit().putString(KEY_LAST_MEDIA_ID, mediaId).apply()
    }

    fun getLastMediaId(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_MEDIA_ID, "") ?: ""

    /** 歌曲缓存（边听边存）开关，默认关闭 */
    fun setCacheEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply()
    }

    fun isCacheEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CACHE_ENABLED, false)

    /** 缓存上限（MB），默认 1GB */
    fun setCacheMaxMb(ctx: Context, mb: Int) {
        prefs(ctx).edit().putInt(KEY_CACHE_MAX_MB, mb).apply()
    }

    fun getCacheMaxMb(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CACHE_MAX_MB, 1024)

    /** 打开应用时是否自动恢复播放（默认 false：仅恢复曲目位置，用户手动播放） */
    fun isAutoResumePlay(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RESUME, false)

    fun setAutoResumePlay(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }
}
