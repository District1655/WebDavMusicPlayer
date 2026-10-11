# ---------- Media3 / ExoPlayer ----------
# Media3 自带 consumer-rules，以下为兜底
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------- OkHttp ----------
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------- 数据类 ----------
-keep class com.webdav.music.data.Track { *; }

# ---------- Kotlin Metadata ----------
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlin.Metadata *;
}
