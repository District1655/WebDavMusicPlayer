# WebDavMusicPlayer 技术文档（REFERENCE）

> 维护用速查文档，防止上下文丢失。改代码后请同步更新本文件。
> 最后更新：2026-10-10（CODE_REVIEW 全部修复完成）

## 1. 项目概况

- **定位**：Android 车机 WebDAV 音乐播放器，横竖屏双布局，CarPlay 风格
- **包名**：`com.webdav.music`
- **环境**：minSdk 29（Android 10）/ targetSdk 34 / Kotlin 1.9.22
- **构建**：JDK 17（`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`）+ Gradle 8.14.3（`D:\android-tools\gradle-8.14.3`）+ Android SDK（`D:\android-tools\android-sdk`）+ AGP 8.5.0
- **依赖**：Media3 1.3.1（exoplayer/session/datasource-okhttp/datasource/database/common）、OkHttp 4.12、coroutines 1.7.3、security-crypto 1.1.0-alpha06
- **关键约束**：Android 10 车机禁用 Room/KSP/Kapt（闪退），数据库一律 SQLiteOpenHelper 手写（见 TrackStore）
- **产物**：签名 APK 同步到 `X:\webdav\WebDavMusicPlayer_release_20261010.apk`（5.0MB，v3 签名）
- **测试**：adb 连接 `192.168.31.43:5555`（横屏 1280x720 车机模拟器）

## 2. 构建与发布流程（固定命令）

```powershell
# 1) 构建（cwd = d:\trae\WebDavMusicPlayer）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "D:\android-tools\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;D:\android-tools\gradle-8.14.3\bin;$env:PATH"
gradle assembleRelease

# 2) 单元测试
gradle testDebugUnitTest

# 3) AOSP platform 系统签名（v3）
$env:PATH = "$env:JAVA_HOME\bin;D:\android-tools\android-sdk\build-tools\34.0.0;$env:PATH"
Copy-Item "app\build\outputs\apk\release\app-release-unsigned.apk" "app\build\outputs\apk\release\app-release-signed.apk" -Force
apksigner sign --key "D:\trae\tool\签名\android 签名\platform.pk8" --cert "D:\trae\tool\签名\android 签名\platform.x509.pem" "app\build\outputs\apk\release\app-release-signed.apk"
apksigner verify --verbose "app\build\outputs\apk\release\app-release-signed.apk"

# 4) 安装测试
adb -s 192.168.31.43:5555 install -r "app\build\outputs\apk\release\app-release-signed.apk"

# 5) 同步到 X 盘
Copy-Item "app\build\outputs\apk\release\app-release-signed.apk" "X:\webdav\WebDavMusicPlayer_release_20261010.apk" -Force
```

坑：`mergeDexRelease` 文件被占用导致构建失败 → `gradle --stop` 停 daemon + 删 `app\build\intermediates\dex\release\mergeDexRelease` 后重试。

## 3. 代码结构

```
com.webdav.music/
├── MainActivity.kt          # 唯一 Activity：横竖屏共用，全部 UI 逻辑（1254 行）
├── data/
│   ├── Track.kt             # path/name/size/lrcPath/scannedAt
│   ├── TrackStore.kt        # SQLiteOpenHelper v2（v2 加 lrcPath 列，ALTER 不丢数据）
│   └── WebDavPrefs.kt       # EncryptedSharedPreferences：密码 AES256_GCM 加密，兼容旧版明文迁移
├── util/
│   └── LyricUtils.kt        # 纯函数：parseLrc/displayTitle/songTitleArtist/formatTime/lrcKey/md5Key（无 Android 依赖，可单元测试）
├── webdav/
│   ├── WebDavClient.kt      # PROPFIND Depth:infinity 一次拉全树；scanMusic；fetchText（lrc）
│   ├── WebDavDataSourceFactory.kt  # OkHttp DataSource 工厂（带 Basic Auth）
│   ├── OnlineMeta.kt        # 在线封面+歌词（多源级联兜底，见 §6）
│   ├── OkHttpHolder.kt      # OkHttpClient 单例（按 trustSelfSigned 缓存），全局连接池复用
│   └── SslHelper.kt         # SSL 配置统一管理（默认系统 CA，可选信任自签证书）
├── playback/
│   ├── PlaybackService.kt   # MediaSessionService + ExoPlayer；switchableFactory 可切换数据源
│   └── MediaCache.kt        # SimpleCache 单例（LRU，无 DatabaseProvider）
└── ui/
    └── TrackAdapter.kt      # 列表适配器（dark 玻璃布局、高亮色、notifyItemChanged 精确刷新）

test/
└── com/webdav/music/util/
    └── LyricUtilsTest.kt    # 29 个单元测试（parseLrc/displayTitle/songTitleArtist/formatTime/lrcKey/md5Key）
```

**资源文件：**
- `res/values/strings.xml`：全部 UI 文本（35+ 条），包括 string-array（theme_names、play_mode_names）
- `res/xml/network_security_config.xml`：仅信任系统 CA，禁止明文流量
- `proguard-rules.pro`：Media3/OkHttp/Track keep 规则，release 启用 minify+shrink
- `app/build.gradle`：testImplementation junit 4.13.2 + kotlinx-coroutines-test 1.7.3

## 4. 布局体系（横竖屏 + 弹层）

- `layout/activity_main.xml`（竖屏）：根 **FrameLayout** 三层
  1. `bgImage`（全屏自定义背景图，gone）+ 主题渐变在 window/rootContainer 背景
  2. 主页竖排：顶部状态卡（tvStatus+btnScan/btnHome/btnSettings）→ 曲库列表 → 底部播放栏
  3. `playerPage`（全屏播放页，默认 gone）：顶部返回 ˅ → 260dp 圆角封面 → 歌名/歌手/歌词 → 底部进度+控制行
  4. `queuePanel`（播放列表弹层，默认 gone）：遮罩 + 渐变卡片，进出有动画
- `layout-land/activity_main.xml`（横屏）：左 32% 曲库列表（顶部状态卡：tvStatus 在上、三按钮 weight 等宽 44dp 高）+ 右 68% 播放面板（歌名在封面上方、歌词在右、时间合并显示 `00:34/02:55`、播放模式/收藏在控制行两端）
- **共用 ID 双布局必须一致**：recyclerView/btnScan/btnSettings/btnHome/tvStatus/progressBar/playerBar/tvNowPlaying/seekBar/btnPlayPause/btnPrev/btnNext
- **竖屏专属 ID**（横屏 findViewById 返回 null，代码可空处理）：tvArtist、playerPage、btnCollapse、albumArtBig、noteIconBig、pageLyrics 三行、tvPageCurrent/tvPageTotal、btnPageMode/btnPagePrev/btnPagePlay/btnPageNext/btnQueue、queuePanel/queueMask/queueCard/queueList/btnQueueClose、bgImage
- 列表 item：`item_track.xml`（浅色，未用）/ `item_track_dark.xml`（暗色玻璃，全局实际用这套）
- 按钮规范：次级键 48dp 圆（player_btn_bg 毛玻璃底、padding 12dp）、主播放键 72dp、横屏顶部三按钮等宽 44dp 高（minWidth/minHeight/inset 清零，防 MaterialButton 撑变形）

## 5. 主题系统

- 主题名称改为 `strings.xml` 的 `string-array` 资源（`theme_names`），代码中 `THEME_NAMES` 为 lazy 加载
- `MainActivity` companion object 内常量（改主题在此处）：
  - `THEME_GRADS`：三色对角渐变（TL_BR），window 背景 + rootContainer + pageBg + 弹窗（themeDialogBg 22dp 圆角）+ queueCard 共用
  - `THEME_ACCENTS` / `THEME_ACCENT_LIGHTS`：强调色（封面渐变、列表高亮、竖屏进度条）
- 珍珠白用**中灰珍珠**（#A9AAB8→#7E8093），不是纯浅色——白字可读性优先，改浅了全部字体看不清
- 主题即时生效：applyTheme() 刷 window/背景/封面/对话框/adapter；对话框开着时切主题同步刷新（settingsDialog 引用）
- 主题选择对话框：**自定义 item_theme_option.xml（CheckedTextView 白字）**——AppCompat 的 textColorAlertDialogListItem overlay 不可靠，别回退

## 6. 核心机制

### 6.1 播放链路
MediaController(↔PlaybackService) → ExoPlayer ← DefaultMediaSourceFactory ← **switchableFactory**（DataSource.Factory 接口内部持 delegate，可热换）
- delegate：关缓存 = WebDavDataSourceFactory；开缓存 = CacheDataSource(SimpleCache + WebDav upstream, FLAG_IGNORE_CACHE_ON_ERROR)
- 缓存设置变化 → `PlaybackService.onCacheConfigChanged()` → 子线程 setDelegate（当前歌不打断，下一首生效）；上限变化 → MediaCache.get() 检测 builtForBytes 不符自动 release 重建
- MediaCache：SimpleCache(File, LeastRecentlyUsedCacheEvictor) 两参构造（1.3.1 无 NoOpDatabaseProvider！getKeys() 而非 getCacheKeys()——已用 javap 验证，别改回）

### 6.2 歌词/封面（LyricUtils + OnlineMeta.kt + MainActivity）
- 纯函数已提取到 `LyricUtils.kt`（无 Android 依赖，29 个单元测试覆盖）
- 文件名解析：`LyricUtils.songTitleArtist()` 按 " - " 分割；歌词显示三行（上一句/当前句白色加粗/下一句），500ms ticker 同步
- 本地优先：同名 .lrc（扫描时配对存 Track.lrcPath）
- 在线级联（任一命中即止）：**lrclib.net → api.lrc.cx → QQ音乐 → 酷狗 → lyrics.ovh → 网易云**（封面：网易云 → QQ音乐 → 酷狗 → iTunes）
- 封面缓存 filesDir/covers/<md5>.jpg；歌词缓存 filesDir/lyrics/<md5>.lrc
- **预加载**：当前曲封面+歌词加载完成后，自动预加载下一曲（封面+歌词+音频前 1MB 写入 SimpleCache）
- **旋转重建坑**：controller 连接早于曲库加载时，loadCoverFor/loadLrcFor 找不到曲目设置 `lrcDeferred`/`coverDeferred` 标记，曲库就绪后仅重试一次

### 6.3 续播/列表定位
- onStop 存 lastMediaId → 下次启动 resumeLastPlay()（controller 就绪或曲库就绪两个入口都试，服务在播/用户已点击则跳过；找不到该曲播第一首）
- scrollToCurrent()：**绕过高亮去重强制滚动**（rotation 后高亮已标记但从未滚动过）；controller 连接后 + 曲库加载后都调用，post 到布局完成，无高度时 scrollToPosition 兜底

### 6.4 分辨率自适应 + 刘海屏
- adaptDisplayDensity()：onCreate 首行调用，**方向感知**——竖屏短边恒 360dp（density=短边px/360）；横屏高度恒 480dp（density=高度px/480，1280x720→1.5→逻辑853x480dp）。横屏按 360dp 逻辑高会装不下右侧播放面板（内容约 430dp），已踩坑。保留 fontScale；旋转重建自动重算
- 刘海屏：cutoutMode ALWAYS(30+)/SHORT_EDGES(29) + setDecorFitsSystemWindows(false) + window 背景设主题渐变（否则刘海区露白）+ 沉浸式隐藏状态/导航栏（onWindowFocusChanged 重申）

### 6.5 自定义背景图
- PhotoPicker(bgPicker) → 复制到 filesDir/bg.jpg → bgImage centerCrop 全屏（横竖屏都有该图层）
- 恢复默认 = 删文件；玻璃卡片（btn_glass_bg/card_bg_dark/player_glass_bg）半透明，背景图可透出

### 6.6 其他
- 收藏爱心：SharedPreferences 按 path 持久化；播放模式（顺序/随机/单曲/列表）：ExoPlayer shuffleMode+repeatMode，prefs 持久化
- 续播开关：`WebDavPrefs.isAutoResumePlay`（默认关闭，仅恢复位置不自动播放）
- 设置保存前强制 WebDAV 连接验证（listDirectory("/")）；保存后无条件调用 `PlaybackService.onCacheConfigChanged()` 重建数据源
- OkHttpClient 全局单例复用（`OkHttpHolder`），SSL 配置由 `SslHelper` 统一管理
- 回桌面按钮：ACTION_MAIN+CATEGORY_HOME
- 横屏进度 ticker 500ms（progressRunnable，onStart 启动/onStop 移除）
- 前台服务通知由 Media3 `MediaSessionService` 自动管理（startForeground/stopForeground + 媒体通知）

## 7. 历史坑速查（改代码前必读）

| 坑 | 结论 |
|---|---|
| **Service 构造期读 prefs** | 字段初始化器里调 WebDavPrefs（内部用 applicationContext）在 Service 反射实例化时 NPE——Service 构造期尚未 attach。任何依赖 Context 的初始化必须放 onCreate（super.onCreate 之后）。崩溃日志见 files/crash/ |
| Android 10 Room/KSP | 闪退，用 SQLiteOpenHelper |
| LinearLayout 根放全屏子页 | 只会上下排不会叠加，全屏页/弹层必须挂根 FrameLayout 下 |
| weight 均分按钮 | 被拉宽成椭圆，圆按钮必须固定尺寸+Space 均分间隙 |
| MaterialButton 默认 minWidth/minHeight/inset | wrap_content 按钮大小不一，统一尺寸需清零这三个属性 |
| 主题渐变改浅色 | 白字全部失效（珍珠白教训），最浅只能到中灰 |
| theme overlay 改列表文字色 | textColorAlertDialogListItem 不可靠，用自定义 item 布局 |
| media3 1.3.1 | 无 NoOpDatabaseProvider；SimpleCache 删除用 getKeys()+removeResource() |
| dex 文件被占用 | gradle --stop + 删 mergeDexRelease 目录重试 |
| robocopy 退出码 | <8 都是成功，勿当失败处理 |
| 横屏三按钮图标 | ic_home 曾是 32dp 导致按钮不齐，图标统一 24dp |
| WebDAV 凭据更新后 401 | 保存后必须无条件调用 onCacheConfigChanged() 重建数据源，不能仅靠缓存配置变化判断 |
| 歌词加载曲库未就绪 | 需区分"曲库未就绪"和"无本地 .lrc"，用 lrcDeferred/coverDeferred 标记延迟重试，不能重置 lrcLoadedFor |
| AlertDialog 宽度自适应 | setLayout 必须在 dialog.show() 之后调用，否则 show() 重置窗口布局参数 |
| 预加载资源泄漏 | 切歌或 Activity 退出时必须取消 preloadJob，避免资源泄漏 |

## 8. 改动记录（摘要）

- 2026-10-09：CarPlay 横屏 → 参考图播放页 → 统一背景/歌词/收藏 → 竖屏播放页+播放队列弹层 → 播放模式 → 8 套主题 → 在线歌词/封面（多源级联）→ 刘海屏/分辨率自适应 → 续播记忆 → **歌曲缓存（SimpleCache LRU + 设置开关/上限 512MB~8GB）**
- 2026-10-10：
  - **安全加固**：SSL 证书校验默认系统 CA + 信任自签开关（SslHelper.kt）；密码 EncryptedSharedPreferences 加密（WebDavPrefs.kt）；禁止明文流量（network_security_config.xml）
  - **正确性修复**：队列同步比较 mediaId 集合；TrackAdapter 精确 notifyItemChanged；OkHttpClient 全局单例（OkHttpHolder.kt）；续播开关默认不自动播放；歌词防抖 lrcDeferred/coverDeferred
  - **可维护性**：纯函数提取到 LyricUtils.kt（MainActivity 1365→1254 行）；UI 文本外化 strings.xml（35+ 条）；TrackStore 列索引统一 getColumnIndexOrThrow；ProGuard 启用+keep 规则（APK 18.3→5.0MB）
  - **测试**：LyricUtilsTest.kt 29 个单元测试（parseLrc/displayTitle/songTitleArtist/formatTime/lrcKey/md5Key）
  - **功能增强**：封面/歌词多接口兜底（网易云/QQ/酷狗/iTunes + lrclib/api.lrc.cx/lyrics.ovh）；预加载机制（封面+歌词+音频前 1MB）；歌曲列表仅显示「歌手 - 歌名」
  - **CODE_REVIEW.md 全部 15 项修复完成**，P0/P1/P2/P3 待办清单全部 `[x]`
