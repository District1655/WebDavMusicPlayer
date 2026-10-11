package com.webdav.music.data

/**
 * 音乐曲目实体（本地缓存 WebDAV 扫描结果）
 */
data class Track(
    /** WebDAV 路径，如 /张宇 - 雨一直下.mp3，全局唯一 */
    val path: String,
    /** 显示名（优先用 displayname，回退到文件名） */
    val name: String,
    /** 文件大小（字节） */
    val size: Long,
    /** 是否目录（扫描时遇到目录不存入，此处恒为 false） */
    val isDirectory: Boolean = false,
    /** 最后扫描时间戳 */
    val scannedAt: Long = System.currentTimeMillis(),
    /** 同名歌词文件（.lrc）的 WebDAV 路径，无则 null */
    val lrcPath: String? = null
)
