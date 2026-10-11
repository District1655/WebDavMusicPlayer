# WebDavMusicPlayer

Android WebDAV 音乐播放器，横竖屏双布局。

## 功能特性

- **WebDAV 音乐播放**：支持 PROPFIND 全树扫描，边听边缓存（SimpleCache LRU）
- **横竖屏双布局**：横屏左列表+右播放面板（车机），竖屏全屏播放页+播放队列弹层
- **歌词同步**：逐行/逐字高亮，横屏三行+竖屏当前句，支持增强型 LRC `<mm:ss.xx>`
- **在线封面/歌词**：多接口级联兜底（网易云/QQ/酷狗/iTunes + lrclib/api.lrc.cx/lyrics.ovh）
- **预加载**：当前曲封面+歌词加载完成后自动预加载下一曲（封面+歌词+音频前 1MB）
- **8 套主题**：星云紫/森林绿/网易红/深海蓝/落日橙/珍珠白/透明毛玻璃/雅致黑
- **分辨率自适应**：竖屏短边恒 360dp，横屏高度恒 480dp，刘海屏沉浸
- **续播记忆**：记录上次播放位置，可配置开关控制是否自动续播
- **收藏/播放模式**：顺序/随机/单曲循环/列表循环
- **安全加固**：SSL 证书校验（默认系统 CA + 自签开关）、密码加密存储、禁止明文流量

## 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 1.9.22 |
| AGP | 8.5.0 |
| Media3 (ExoPlayer + MediaSession) | 1.3.1 |
| OkHttp | 4.12.0 |
| Coroutines | 1.7.3 |
| security-crypto | 1.1.0-alpha06 |
| minSdk / targetSdk | 29 / 34 |

## 项目结构

```
com.webdav.music/
├── MainActivity.kt            # 唯一 Activity（1254 行）
├── data/
│   ├── Track.kt               # 数据模型
│   ├── TrackStore.kt          # SQLiteOpenHelper（手写，不用 Room）
│   └── WebDavPrefs.kt         # EncryptedSharedPreferences
├── util/
│   └── LyricUtils.kt          # 纯函数（parseLrc/displayTitle/formatTime 等）
├── webdav/
│   ├── WebDavClient.kt        # WebDAV PROPFIND 客户端
│   ├── WebDavDataSourceFactory.kt
│   ├── OnlineMeta.kt         # 在线封面+歌词多源级联
│   ├── OkHttpHolder.kt        # OkHttpClient 单例
│   └── SslHelper.kt           # SSL 配置管理
├── playback/
│   ├── PlaybackService.kt     # MediaSessionService + ExoPlayer
│   └── MediaCache.kt          # SimpleCache 单例
└── ui/
    └── TrackAdapter.kt        # 列表适配器

app/src/test/
└── com/webdav/music/util/
    └── LyricUtilsTest.kt      # 29 个单元测试
```

## 构建

### 环境要求

- JDK 17
- Gradle 8.14.3
- Android SDK 34 (build-tools 34.0.0)

### 构建命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "D:\android-tools\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;D:\android-tools\gradle-8.14.3\bin;$env:PATH"

# 构建 Release
gradle assembleRelease

# 单元测试
gradle testDebugUnitTest
```

### 签名

使用 AOSP platform 签名（适配车机系统权限）：

```powershell
$env:PATH = "$env:JAVA_HOME\bin;D:\android-tools\android-sdk\build-tools\34.0.0;$env:PATH"
Copy-Item "app\build\outputs\apk\release\app-release-unsigned.apk" "app\build\outputs\apk\release\app-release-signed.apk" -Force
apksigner sign --key "D:\trae\tool\签名\android 签名\platform.pk8" --cert "D:\trae\tool\签名\android 签名\platform.x509.pem" "app\build\outputs\apk\release\app-release-signed.apk"
apksigner verify --verbose "app\build\outputs\apk\release\app-release-signed.apk"
```

## 测试

```powershell
# 连接模拟器
adb connect 192.168.31.43:5555

# 安装
adb -s 192.168.31.43:5555 install -r "app\build\outputs\apk\release\app-release-signed.apk"

# 启动
adb -s 192.168.31.43:5555 shell am start -n com.webdav.music/.MainActivity
```

测试服务器：`https://fn.827612614.xyz:58443`（用户名/密码见项目配置）

## 文档

- [REFERENCE.md](REFERENCE.md) - 技术速查文档（架构、机制、历史坑）
- [CODE_REVIEW.md](CODE_REVIEW.md) - 代码审查报告（15 项全部修复完成）

## 车机适配

- 刘海屏：`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + 沉浸式隐藏状态/导航栏
- 分辨率自适应：`adaptDisplayDensity()` 方向感知，竖屏 360dp / 横屏 480dp
- 方向盘按键：`KEYCODE_MEDIA_PLAY_PAUSE` (85) 走 MediaSession 路由
- 横屏 1280x720 车机模拟器验证通过
