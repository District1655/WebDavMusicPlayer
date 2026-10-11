# WebDavMusicPlayer 项目审查报告

> 审查日期：2026-10-10
> 审查范围：全量源代码、配置、资源文件
> 审查维度：安全性、正确性、性能、可维护性、健壮性

## 一、项目概况

Android 车机 WebDAV 音乐播放器，Kotlin + Media3 (ExoPlayer) + OkHttp + Coroutines，手写 SQLiteOpenHelper 替代 Room（规避 Android 10 车机崩溃）。

| 维度 | 详情 |
|------|------|
| 包名 | `com.webdav.music` |
| minSdk / targetSdk | 29 / 34 |
| Kotlin / AGP | 1.9.22 / 8.5.0 |
| 核心依赖 | Media3 1.3.1、OkHttp 4.12、Coroutines 1.7.3 |
| 代码规模 | MainActivity 1242 行，共 10 个 Kotlin 文件 |

## 二、架构评估

```
UI 层 (MainActivity / TrackAdapter)
  ↕ MediaController
播放层 (PlaybackService / MediaCache)
  ↕ DataSource.Factory
网络层 (WebDavClient / WebDavDataSourceFactory / OnlineMeta)
  ↕
数据层 (TrackStore / WebDavPrefs)
```

**优点：**
- 播放服务与 UI 通过 MediaSession/MediaController 解耦，支持后台播放和系统媒体按钮
- `switchableFactory` 设计巧妙，缓存开关热切换不打断当前播放
- 横竖屏双布局通过可空 View 引用优雅处理差异
- 崩溃日志自动采集机制实用

**主要架构问题：** MainActivity 承担过多职责（UI、播放控制、歌词、封面、主题、设置、收藏、背景图），1242 行的上帝类不利于维护。

---

## 三、问题清单

### 🔴 严重问题（安全）

#### 1. 全局信任所有 SSL 证书，存在中间人攻击风险 ✅ 已修复

**位置：** [WebDavClient.kt#L42-L56](app/src/main/java/com/webdav/music/webdav/WebDavClient.kt)、[WebDavDataSourceFactory.kt#L34-L57](app/src/main/java/com/webdav/music/webdav/WebDavDataSourceFactory.kt)

**修复内容：**
- 新增 [SslHelper.kt](app/src/main/java/com/webdav/music/webdav/SslHelper.kt) 统一管理 SSL 配置
- 默认不配置自定义 TrustManager，使用系统 CA 校验
- 新增 `trustSelfSigned` 选项，仅用户在设置中主动开启时才信任自签证书
- 设置界面新增"信任自签 SSL 证书"开关

#### 2. WebDAV 密码明文存储在 SharedPreferences ✅ 已修复

**位置：** [WebDavPrefs.kt#L22-L28](app/src/main/java/com/webdav/music/data/WebDavPrefs.kt)

**修复内容：**
- 引入 `androidx.security:security-crypto:1.1.0-alpha06`
- 密码迁移到 `EncryptedSharedPreferences`（AES256_GCM + Android Keystore）
- 兼容旧版明文密码：首次读取时自动迁移到加密存储并删除明文字段
- URL/用户名等非敏感字段仍存普通 SharedPreferences

#### 3. 允许明文 HTTP 流量 ✅ 已修复

**位置：** [AndroidManifest.xml#L16](app/src/main/AndroidManifest.xml)、[OnlineMeta.kt#L135](app/src/main/java/com/webdav/music/webdav/OnlineMeta.kt)

**修复内容：**
- 新增 [network_security_config.xml](app/src/main/res/xml/network_security_config.xml)，`trust-anchors` 仅信任系统 CA
- AndroidManifest 移除 `usesCleartextTraffic="true"`，改用 `networkSecurityConfig`
- OnlineMeta 中所有公网 API 从 `http://` 改为 `https://`（仅 `mobilecdn.kugou.com` 因不支持 HTTPS 保留 HTTP）

---

### 🟠 中等问题（正确性 / 性能）

#### 4. 播放队列同步逻辑存在缺陷，可能播放陈旧曲目 ✅ 已修复

**位置：** [MainActivity.kt#L335-L342](app/src/main/java/com/webdav/music/MainActivity.kt)

**修复内容：**
- `pushQueueToPlayer` 改为比较 mediaId 集合（`currentIds == newIds`），而非仅比较数量
- 数量相同但文件已替换（如删除 A 添加 B）时也会正确更新播放队列

#### 5. TrackAdapter 高亮刷新用 notifyDataSetChanged，全列表重绘 ✅ 已修复

**位置：** [TrackAdapter.kt#L29-L37](app/src/main/java/com/webdav/music/ui/TrackAdapter.kt)

**修复内容：**
- `setCurrentPath` 改为记录旧/新位置，调用 `notifyItemChanged(oldIdx)` 和 `notifyItemChanged(newIdx)`
- 仅刷新两个受影响的 item，避免全列表重绘

#### 6. OkHttpClient 未复用，每次请求新建连接池 ✅ 已修复

**修复内容：**
- 新增 [OkHttpHolder.kt](app/src/main/java/com/webdav/music/webdav/OkHttpHolder.kt) 单例管理器，按 `trustSelfSigned` 缓存共享 OkHttpClient
- [WebDavClient.kt](app/src/main/java/com/webdav/music/webdav/WebDavClient.kt) 改用 `OkHttpHolder.forWebDav()`，扫描和歌词下载复用同一连接池
- [WebDavDataSourceFactory.kt](app/src/main/java/com/webdav/music/webdav/WebDavDataSourceFactory.kt) 基于共享实例 `newBuilder()` 派生，仅定制播放超时和 Auth 拦截器
- [OnlineMeta.kt](app/src/main/java/com/webdav/music/webdav/OnlineMeta.kt) 已有 lazy 单例，保持不变
- SSL 配置统一由 `SslHelper` + `OkHttpHolder` 管理，消除重复代码

#### 7. 打开应用自动恢复播放可能不符合用户预期 ✅ 已修复

**位置：** [MainActivity.kt#L300-L312](app/src/main/java/com/webdav/music/MainActivity.kt)、[WebDavPrefs.kt](app/src/main/java/com/webdav/music/data/WebDavPrefs.kt)

**修复内容：**
- `resumeLastPlay` 默认仅恢复曲目位置（`seekToDefaultPosition + prepare`），不自动调用 `play()`
- 新增"打开应用自动续播"设置开关（`WebDavPrefs.isAutoResumePlay`），默认关闭
- 设置界面新增对应 Switch 控件，用户可自行开启自动续播

#### 8. loadLrcFor 在曲库未就绪时可能重复触发网络请求 ✅ 已修复

**位置：** [MainActivity.kt#L747-L766](app/src/main/java/com/webdav/music/MainActivity.kt)

**修复内容：**
- 曲库未就绪时不再重置 `lrcLoadedFor = null`，改为设置 `lrcDeferred` 标记，防止 `updatePlayerUi` 每次 tick 重试
- `loadCoverFor` 同样改为 `coverDeferred` 标记
- `loadCachedTracks` 曲库就绪回调中检查 deferred 标记，仅重试一次加载歌词/封面

---

### 🟡 轻微问题（可维护性 / 代码质量）

#### 9. MainActivity 职责过重 ✅ 已优化

将 `parseLrc`/`displayTitle`/`songTitleArtist`/`formatTime`/`lrcKey`/`md5Key`/`parseArtistName` 等纯函数提取到 [LyricUtils.kt](app/src/main/java/com/webdav/music/util/LyricUtils.kt)，MainActivity 从 1365 行减至 1254 行。

#### 10. 大量 UI 文本硬编码在 Kotlin 中 ✅ 已修复

所有硬编码中文 UI 文本迁移到 [strings.xml](app/src/main/res/values/strings.xml)，包括 35 处 Toast/状态文本、布局 XML 文本、THEME_NAMES/PLAY_MODE_NAMES 改为 string-array 资源。

#### 11. SSL 配置代码重复 ✅ 已修复

已由 #6 修复 — [SslHelper.kt](app/src/main/java/com/webdav/music/webdav/SslHelper.kt) 统一管理 SSL 配置，[OkHttpHolder.kt](app/src/main/java/com/webdav/music/webdav/OkHttpHolder.kt) 单例复用。

#### 12. release 构建未启用混淆 ✅ 已修复

**位置：** [app/build.gradle](app/build.gradle)、[proguard-rules.pro](app/proguard-rules.pro)

已启用 `minifyEnabled true` + `shrinkResources true`，proguard-rules.pro 添加 Media3/OkHttp/Track keep 规则。APK 体积从 18.3MB 降至 5.0MB。

#### 13. TrackStore 列索引获取方式不一致 ✅ 已修复

**位置：** [TrackStore.kt](app/src/main/java/com/webdav/music/data/TrackStore.kt)

`lrcPath` 改用 `getColumnIndexOrThrow`，与其他列一致。

#### 14. 缺少单元测试 ✅ 已修复

新增 [LyricUtilsTest.kt](app/src/test/java/com/webdav/music/util/LyricUtilsTest.kt)，29 个测试覆盖 `parseLrc`(8)、`displayTitle`(6)、`songTitleArtist`(3)、`parseArtistName`(2)、`formatTime`(4)、`lrcKey`(3)、`md5Key`(3)，全部通过。

#### 15. PlaybackService 未显式处理前台通知 ✅ 已确认

**位置：** [AndroidManifest.xml](app/src/main/AndroidManifest.xml)

确认 Media3 1.3.1 `MediaSessionService` 自动管理 `startForeground`/`stopForeground` 和媒体通知。已声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限和 `foregroundServiceType="mediaPlayback"`，新增 `POST_NOTIFICATIONS` 权限适配 Android 13+。

---

## 四、待办清单

### P0 - 安全加固（优先处理）（已实现）

- [x] **SSL 证书校验策略**：默认系统 CA 校验，新增"信任自签证书"开关（[SslHelper.kt](app/src/main/java/com/webdav/music/webdav/SslHelper.kt)）
- [x] **密码加密存储**：引入 `androidx.security:security-crypto`，密码改存 EncryptedSharedPreferences，兼容旧版明文迁移
- [x] **明文流量限制**：新增 `network_security_config.xml` 限定仅信任系统 CA，公网 API 全部升级 HTTPS

### P1 - 正确性与性能修复

- [x] **修复队列同步逻辑**：`pushQueueToPlayer` 改为比较 mediaId 集合，而非仅比较数量
- [x] **OkHttpClient 单例复用**：提取共享 OkHttpClient（[OkHttpHolder.kt](app/src/main/java/com/webdav/music/webdav/OkHttpHolder.kt)），统一 SSL 配置，消除三处重复
- [x] **TrackAdapter 精确刷新**：`setCurrentPath` 改为 `notifyItemChanged` 精确刷新旧/新 item
- [x] **调整续播逻辑**：打开应用时仅恢复位置不自动播放，新增设置开关控制续播行为

### P2 - 可维护性改进

- [x] **拆分 MainActivity**：将 `parseLrc`/`displayTitle`/`songTitleArtist`/`formatTime`/`lrcKey`/`md5Key` 等纯函数提取到 [LyricUtils.kt](app/src/main/java/com/webdav/music/util/LyricUtils.kt)，MainActivity 从 1365 行减至 1254 行
- [x] **文本资源外化**：所有硬编码中文 UI 文本迁移到 [strings.xml](app/src/main/res/values/strings.xml)，包括 MainActivity.kt 中 35 处 Toast/状态文本、布局 XML 文本、string-array 资源
- [x] **统一 TrackStore 列索引**：`lrcPath` 改用 `getColumnIndexOrThrow`，与其他列一致
- [x] **补充 ProGuard 规则**：启用 `minifyEnabled true` + `shrinkResources true`，为 Media3/OkHttp/Track 添加 keep 规则

### P3 - 长期优化

- [x] **补充单元测试**：新增 [LyricUtilsTest.kt](app/src/test/java/com/webdav/music/util/LyricUtilsTest.kt)，29 个测试覆盖 `parseLrc`(8)、`displayTitle`(6)、`songTitleArtist`(3)、`parseArtistName`(2)、`formatTime`(4)、`lrcKey`(3)、`md5Key`(3)
- [x] **前台服务通知确认**：确认 Media3 1.3.1 `MediaSessionService` 自动管理 `startForeground`/`stopForeground` 和媒体通知；AndroidManifest 已声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限和 `foregroundServiceType="mediaPlayback"`；新增 `POST_NOTIFICATIONS` 权限适配 Android 13+
- [x] **歌词加载防抖**：曲库未就绪时用 `lrcDeferred`/`coverDeferred` 标记延迟重试，避免重复触发

---

## 五、总体评价

项目**功能完整度高**，车机场景适配考虑周到（刘海屏、分辨率自适应、横竖屏、方向盘按键），REFERENCE.md 文档质量优秀，历史坑记录详细，体现了实战经验。代码整体健壮，异常处理和资源释放到位。

主要短板集中在**安全性**（SSL 全信任、密码明文）和**可维护性**（MainActivity 过大）。建议优先解决安全问题，再逐步重构 UI 层。
