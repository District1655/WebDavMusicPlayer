package com.webdav.music

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.webdav.music.data.TrackStore
import com.webdav.music.data.Track
import com.webdav.music.data.WebDavPrefs
import com.webdav.music.playback.MediaCache
import com.webdav.music.playback.PlaybackService
import com.webdav.music.ui.TrackAdapter
import com.webdav.music.util.LyricUtils
import com.webdav.music.webdav.OnlineMeta
import com.webdav.music.webdav.WebDavClient
import com.webdav.music.webdav.WebDavDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TrackAdapter
    private lateinit var btnScan: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    // 底部播放栏
    private lateinit var playerBar: View
    private lateinit var tvNowPlaying: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    // 横屏暗色播放面板独有（竖屏布局里不存在这些 ID，所以用可空）
    private var tvTimeCur: TextView? = null
    private var tvTimeTot: TextView? = null
    private var tvLyricPrev: TextView? = null
    private var tvLyricCur: TextView? = null
    private var tvLyricNext: TextView? = null
    private var btnFavorite: ImageButton? = null
    private var btnHome: View? = null
    private var btnPlayMode: ImageButton? = null

    // 播放模式 0顺序 1随机 2单曲 3列表；主题索引（存 ui_config）
    private var playMode = 0
    private var currentTheme = 0
    private val themePrefs by lazy { getSharedPreferences("ui_config", MODE_PRIVATE) }
    private val THEME_NAMES by lazy { resources.getStringArray(R.array.theme_names) }
    private val PLAY_MODE_NAMES by lazy { resources.getStringArray(R.array.play_mode_names) }

    // 竖屏全屏播放页 + 播放列表弹层（仅竖屏布局存在，横屏为 null）
    private var playerPage: View? = null
    private var bgImagePage: ImageView? = null
    private var tvPageTitle: TextView? = null
    private var tvPageArtist: TextView? = null
    private var tvPageLyric: TextView? = null
    private var seekBarPage: SeekBar? = null
    private var tvPageTimeCur: TextView? = null
    private var tvPageTimeTot: TextView? = null
    private var btnPageMode: ImageButton? = null
    private var btnPagePlay: ImageButton? = null
    private var albumArtImageBig: ImageView? = null
    private var noteIconBig: ImageView? = null
    private var queuePanel: View? = null
    private var tvQueueTitle: TextView? = null
    private var queueAdapter: TrackAdapter? = null
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null

    // 歌词状态：有序列表；lrcLoadedFor 记录已加载的 mediaId
    private var lrcLines: List<LyricUtils.LyricLine> = emptyList()
    private var lrcLoadedFor: String? = null
    private var lrcJob: kotlinx.coroutines.Job? = null
    // 曲库未就绪时延迟重试的 mediaId（防止 updatePlayerUi 每次重试）
    private var lrcDeferred: String? = null

    // 在线封面/歌词状态（本地缓存目录：covers/ lyrics/）
    private var albumArtImage: ImageView? = null
    private var noteIcon: ImageView? = null
    private var coverLoadedFor: String? = null
    private var metaJob: kotlinx.coroutines.Job? = null
    private var coverDeferred: String? = null
    private val coverCacheDir by lazy { java.io.File(filesDir, "covers").apply { mkdirs() } }
    // 下一曲预加载（封面+歌词+音频开头），与 lrcJob/metaJob 并列
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var preloadFor: String? = null
    private val lrcCacheDir by lazy { java.io.File(filesDir, "lyrics").apply { mkdirs() } }

    // 收藏（本地 SharedPreferences 按路径记录）
    private val favPrefs by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }

    // 自定义背景图（设置里选择，存储到内部 filesDir/bg.jpg）
    private var bgImage: ImageView? = null
    private val bgFile by lazy { java.io.File(filesDir, "bg.jpg") }
    private val bgPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importBg(it) } }

    // 周期性进度刷新（播放中每 500ms 刷时间/进度条）
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            refreshProgress()
            progressHandler.postDelayed(this, 500)
        }
    }

    private val store by lazy { TrackStore(this) }
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** SeekBar 是否由用户正在拖动（拖动期间不响应 player 进度回调） */
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃日志采集：闪退时堆栈写入 files/crash/，便于无 adb 场景排查
        installCrashHandler()
        // 分辨率自适应：保证 UI 短边恒为 360dp，控件大小随车机屏幕等比缩放
        adaptDisplayDensity()
        // 刘海屏适配：横屏允许内容延伸到刘海/挖孔区（30+ 用 ALWAYS 更彻底）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        playerBar = findViewById(R.id.playerBar)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvNowPlaying?.isSelected = true
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        // 横屏独有：竖屏布局里不存在 → 返回 null，安全忽略
        tvTimeCur = findViewById(R.id.tvTimeCur)
        tvTimeTot = findViewById(R.id.tvTimeTot)
        tvLyricPrev = findViewById(R.id.tvLyricPrev)
        tvLyricCur = findViewById(R.id.tvLyricCur)
        tvLyricNext = findViewById(R.id.tvLyricNext)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnFavorite?.setOnClickListener { toggleFavorite() }
        // 自定义背景图（横竖屏布局都有）
        bgImage = findViewById(R.id.bgImage)
        // 竖屏全屏播放页 + 播放列表弹层（需在 applyBg 前完成绑定，背景才能同步）
        setupPlayerPage()
        applyBg()
        // 在线专辑封面（横屏播放面板）
        albumArtImage = findViewById(R.id.albumArtImage)
        noteIcon = findViewById(R.id.noteIcon)
        // 返回系统桌面
        btnHome = findViewById(R.id.btnHome)
        btnHome?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_cannot_back_home, Toast.LENGTH_SHORT).show()
            }
        }
        // 播放模式：顺序/随机/单曲循环/列表循环
        btnPlayMode = findViewById(R.id.btnPlayMode)
        btnPlayMode?.setOnClickListener { cyclePlayMode() }
        playMode = themePrefs.getInt("play_mode", 0)
        updatePlayModeIcon()

        adapter = TrackAdapter()
        adapter.dark = true // 全局暗色玻璃风格（横竖屏统一）
        adapter.onItemClick = { track -> playTrackFromList(track) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 打开即见：直接读 SQLite 缓存
        loadCachedTracks()

        btnScan.setOnClickListener { startScan() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        // 底部播放栏按钮
        btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        btnNext.setOnClickListener { controller?.seekToNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                val dur = c.duration
                if (dur > 0) {
                    c.seekTo((sb?.progress ?: 0).toLong() * dur / 100)
                }
            }
        })

        // 应用主题配色（根渐变/封面/强调色）
        applyTheme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 隐藏系统状态栏（车机全屏沉浸播放） */
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // 不让 decor 自动加 insets 内边距，内容才能真正铺到刘海区
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars()
                    or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onStart() {
        super.onStart()
        // 创建 MediaController，会自动绑定到 PlaybackService
        if (controller == null) {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            controllerFuture = future
            val mainExecutor = Executor { cmd -> Handler(Looper.getMainLooper()).post(cmd) }
            future.addListener({
                val c = try { future.get() } catch (_: Throwable) { null }
                runOnUiThread {
                    controller = c
                    c?.let { applyPlayMode(it) }
                    c?.addListener(playerListener)
                    updatePlayerUi()
                    scrollToCurrent()
                    resumeLastPlay(adapter.currentList)
                    // 启动进度 ticker
                    progressHandler.removeCallbacks(progressRunnable)
                    progressHandler.post(progressRunnable)
                }
            }, mainExecutor)
        }
    }

    override fun onStop() {
        super.onStop()
        // 记住当前播放曲目，下次打开恢复
        controller?.currentMediaItem?.mediaId?.let {
            if (it.isNotEmpty()) WebDavPrefs.setLastMediaId(this, it)
        }
        progressHandler.removeCallbacks(progressRunnable)
        lrcJob?.cancel()
        preloadJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** 打开 APP 自动恢复上次播放位置：上次的歌还在就恢复到该曲目，不在了就选第一首。
     *  仅恢复位置，用户需手动按播放键；开启"自动续播"设置时才自动播放。 */
    private fun resumeLastPlay(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.playWhenReady) return // 服务仍在播/用户已操作，不打断
        val last = WebDavPrefs.getLastMediaId(this)
        val idx = if (last.isEmpty()) -1 else tracks.indexOfFirst { it.path == last }
        val target = if (idx >= 0) idx else 0
        c.seekToDefaultPosition(target)
        c.prepare()
        if (WebDavPrefs.isAutoResumePlay(this)) c.play()
    }

    /** 从缓存加载曲目列表，并同步给 PlaybackService 作为播放队列 */
    private fun loadCachedTracks() {
        lifecycleScope.launch(Dispatchers.Main) {
            val tracks = withContext(Dispatchers.IO) { store.getAll() }
            adapter.submitList(tracks) {
                // 曲库就绪后刷新一次：旋转重建时 controller 可能先于列表连接，封面/歌词/高亮当时被跳过
                if (controller != null) {
                    // 曲库就绪后重试因列表未就绪而延迟的歌词/封面加载（防抖：仅重试一次）
                    val mediaId = controller?.currentMediaItem?.mediaId
                    if (mediaId != null) {
                        if (lrcDeferred == mediaId) loadLrcFor(mediaId)
                        if (coverDeferred == mediaId) loadCoverFor(mediaId)
                    }
                    updatePlayerUi()
                    scrollToCurrent()
                    resumeLastPlay(tracks)
                }
            }
            // 同步播放队列给 player（用户点击之前先准备好）
            pushQueueToPlayer(tracks)
            tvStatus.text = if (tracks.isEmpty()) {
                getString(R.string.status_empty_library)
            } else {
                getString(R.string.status_track_count, tracks.size)
            }
        }
    }

    /** 把曲目列表设为播放队列（仅在 player 当前队列内容不同时刷新，避免打断播放） */
    private fun pushQueueToPlayer(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        // 比较 mediaId 集合：数量相同但文件已替换（如删除 A 添加 B）也需更新
        val currentIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        val newIds = tracks.map { it.path }.toSet()
        if (currentIds == newIds) return
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
        c.setMediaItems(items)
    }

    /** 点击列表项播放指定曲目 */
    private fun playTrackFromList(track: Track) {
        val c = controller ?: run {
            // 播放服务未就绪：Toast + 状态栏双重提示
            Toast.makeText(this, R.string.toast_service_not_ready, Toast.LENGTH_SHORT).show()
            tvStatus.text = getString(R.string.status_service_starting)
            return
        }
        // 在 adapter 数据里找下标
        val list = adapter.currentList
        val idx = list.indexOfFirst { it.path == track.path }
        if (idx < 0) {
            tvStatus.text = getString(R.string.status_selected, track.name)
            return
        }
        // 如果 player 队列还空，先把当前列表推进去
        if (c.mediaItemCount == 0) pushQueueToPlayer(list)
        c.seekToDefaultPosition(idx)
        c.play()
        playerBar.visibility = View.VISIBLE
        syncListHighlight(track.path)
        // 立即反馈：歌名 + 加载中提示，让用户知道点击生效了
        tvNowPlaying.text = getString(R.string.now_playing_loading, LyricUtils.displayTitle(track.name))
        tvStatus.text = getString(R.string.status_playing, track.name)
    }

    private fun startScan() {
        if (!WebDavPrefs.isConfigured(this)) {
            showSettingsDialog()
            return
        }

        val url = WebDavPrefs.getUrl(this)
        val user = WebDavPrefs.getUser(this)
        val pass = WebDavPrefs.getPass(this)

        btnScan.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_scanning)

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val dav = WebDavClient(url, user, pass, WebDavPrefs.isTrustSelfSigned(this@MainActivity))
                    dav.scanMusic("/") { count ->
                        runOnUiThread { tvStatus.text = getString(R.string.status_discovered, count) }
                    }
                }

                // 音频 + 同名 .lrc 歌词配对（按去扩展名的路径匹配）
                val lrcMap = entries.filter {
                    it.name.substringAfterLast('.', "").lowercase() == "lrc"
                }.associate { e -> LyricUtils.lrcKey(e.href) to e.href }
                val tracks = entries.filter {
                    it.name.substringAfterLast('.', "").lowercase() in WebDavClient.MUSIC_EXTS
                }.map {
                    Track(path = it.href, name = it.name, size = it.size,
                        lrcPath = lrcMap[LyricUtils.lrcKey(it.href)])
                }
                withContext(Dispatchers.IO) {
                    store.replaceAll(tracks)
                }

                tvStatus.text = getString(R.string.status_scan_complete, tracks.size)
                loadCachedTracks()
            } catch (e: Exception) {
                tvStatus.text = getString(R.string.status_scan_failed, e.message ?: "")
            }
            btnScan.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)
        val switchTrust = view.findViewById<android.widget.Switch>(R.id.switchTrustSelfSigned)
        switchTrust.isChecked = WebDavPrefs.isTrustSelfSigned(this)
        val switchAutoResume = view.findViewById<android.widget.Switch>(R.id.switchAutoResume)
        switchAutoResume.isChecked = WebDavPrefs.isAutoResumePlay(this)
        val btnPickBg = view.findViewById<Button>(R.id.btnPickBg)
        val btnClearBg = view.findViewById<Button>(R.id.btnClearBg)
        btnPickBg.setOnClickListener { bgPicker.launch("image/*") }
        btnClearBg.setOnClickListener {
            bgFile.delete()
            bgImage?.let { it.visibility = View.GONE }
            Toast.makeText(this, R.string.toast_bg_reset, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btnTheme).setOnClickListener { showThemeDialog() }

        // ---- 歌曲缓存设置 ----
        val switchCache = view.findViewById<android.widget.Switch>(R.id.switchCache)
        val btnCacheSize = view.findViewById<Button>(R.id.btnCacheSize)
        val tvCacheUsed = view.findViewById<TextView>(R.id.tvCacheUsed)
        val btnCacheClear = view.findViewById<Button>(R.id.btnCacheClear)
        val cacheSizes = intArrayOf(512, 1024, 2048, 4096, 8192)
        val oldCacheEnabled = WebDavPrefs.isCacheEnabled(this)
        val oldCacheMaxMb = WebDavPrefs.getCacheMaxMb(this)

        switchCache.isChecked = oldCacheEnabled
        fun cacheSizeLabel(mb: Int): String =
            if (mb >= 1024 && mb % 1024 == 0) getString(R.string.cache_limit_gb, mb / 1024) else getString(R.string.cache_limit_mb, mb)
        var selCacheMb = oldCacheMaxMb
        btnCacheSize.text = cacheSizeLabel(selCacheMb)
        btnCacheSize.setOnClickListener {
            val idx = (cacheSizes.indexOf(selCacheMb) + 1) % cacheSizes.size
            selCacheMb = cacheSizes[idx]
            btnCacheSize.text = cacheSizeLabel(selCacheMb)
        }

        lifecycleScope.launch {
            val used = withContext(Dispatchers.IO) { MediaCache.usedBytes(this@MainActivity) }
            val mb = used / 1024.0 / 1024.0
            tvCacheUsed.text = getString(R.string.cache_used, mb)
        }
        btnCacheClear.setOnClickListener {
            btnCacheClear.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { MediaCache.clear(this@MainActivity) }
                tvCacheUsed.text = getString(R.string.cache_used_empty)
                btnCacheClear.isEnabled = true
                Toast.makeText(this@MainActivity, R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        etUrl.setText(WebDavPrefs.getUrl(this))
        etUser.setText(WebDavPrefs.getUser(this))
        etPass.setText(WebDavPrefs.getPass(this))

        // positive 按钮先用 null 关闭 auto-dismiss，手动控制以便验证失败时保留对话框
        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_WebDavPlayer_Alert)
            .setView(view)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        // 对话框底跟随主题渐变（圆角），与全局主题一致
        dialog.window?.setBackgroundDrawable(themeDialogBg())
        settingsDialog = dialog
        dialog.setOnDismissListener { settingsDialog = null }

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val url = etUrl.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()

                if (url.isEmpty() || user.isEmpty()) {
                    Toast.makeText(this, R.string.toast_fill_url_user, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(this, R.string.toast_url_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                btn.isEnabled = false
                btn.text = getString(R.string.btn_verifying)
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            WebDavClient(url, user, pass, switchTrust.isChecked).listDirectory("/")
                            true
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity,
                                    getString(R.string.toast_connect_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                            false
                        }
                    }
                    if (!isActive) return@launch
                    if (ok) {
                        WebDavPrefs.save(this@MainActivity, url, user, pass)
                        WebDavPrefs.setTrustSelfSigned(this@MainActivity, switchTrust.isChecked)
                        WebDavPrefs.setAutoResumePlay(this@MainActivity, switchAutoResume.isChecked)
                        // WebDAV 凭据可能已变化，必须重建数据源（否则播放服务仍用旧的空凭据导致 401）
                        PlaybackService.onCacheConfigChanged()
                        // 缓存设置变化时额外持久化缓存开关/上限
                        val cacheChanged = switchCache.isChecked != oldCacheEnabled ||
                                selCacheMb != oldCacheMaxMb
                        if (cacheChanged) {
                            WebDavPrefs.setCacheEnabled(this@MainActivity, switchCache.isChecked)
                            WebDavPrefs.setCacheMaxMb(this@MainActivity, selCacheMb)
                        }
                        Toast.makeText(this@MainActivity, R.string.toast_saved_ok, Toast.LENGTH_SHORT).show()
                        tvStatus.text = getString(R.string.status_settings_saved)
                        dialog.dismiss()
                    }
                    btn.isEnabled = true
                    btn.text = getString(R.string.btn_save)
                }
            }
        }
        dialog.show()
        // 对话框宽度按逻辑分辨率自适应（adaptDisplayDensity 后：
        // 竖屏逻辑宽恒 360dp → 对话框 330dp ≈ 92%；横屏逻辑高恒 480dp → 对话框 540dp 居中）
        // 必须在 show() 之后调用，AlertDialog.show() 会重置窗口布局参数
        val dm = resources.displayMetrics
        val targetDp = if (dm.heightPixels >= dm.widthPixels) 330f else 540f
        dialog.window?.setLayout(
            (targetDp * dm.density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------- 播放器 UI 刷新 ----------

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayerUi()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    // 加载中：歌名后追加提示
                    val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?: getString(R.string.unknown_track)
                    tvNowPlaying.text = getString(R.string.now_playing_loading, LyricUtils.displayTitle(title))
                }
                Player.STATE_READY -> {
                    // 加载完成，updatePlayerUi 会刷新为标准标题
                }
                Player.STATE_ENDED -> {
                    // 自动续曲已由 updatePlayerUi 处理
                }
                Player.STATE_IDLE -> {
                    // 无可播放内容
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 网络异常、401、URL 错误等导致播放失败 → 明确提示
            val cause = error.cause?.message ?: error.message ?: getString(R.string.unknown_error)
            tvNowPlaying.text = getString(R.string.play_failed, cause)
            btnPlayPause.setImageResource(R.drawable.ic_play)
            Toast.makeText(this@MainActivity,
                getString(R.string.play_failed, cause), Toast.LENGTH_LONG).show()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 播放/暂停状态变化，UI 通过 updatePlayerUi 刷新
        }
    }

    private fun updatePlayerUi() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playerBar.visibility = View.GONE
            return
        }
        playerBar.visibility = View.VISIBLE
        val rawTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: getString(R.string.unknown_track)
        // BUFFERING 时保留"加载中"提示，不覆盖
        if (c.playbackState != Player.STATE_BUFFERING) {
            tvNowPlaying.text = LyricUtils.displayTitle(rawTitle)
        }
        val mediaId = c.currentMediaItem?.mediaId
        updateFavIcon(mediaId)
        if (mediaId != lrcLoadedFor) loadLrcFor(mediaId)
        if (mediaId != coverLoadedFor) loadCoverFor(mediaId)
        syncListHighlight(mediaId)
        refreshProgress()
        btnPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        // 竖屏播放页同步（歌名/歌手/播放钮）
        if (c.playbackState != Player.STATE_BUFFERING) {
            tvPageTitle?.text = LyricUtils.displayTitle(rawTitle)
            tvPageArtist?.text = LyricUtils.parseArtistName(rawTitle)
        }
        btnPagePlay?.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (!c.isPlaying && c.playbackState == Player.STATE_ENDED) {
            // 自动下一曲
            c.seekToNext()
            c.play()
        }
    }

    /** 仅刷新进度条 + 时间 + 歌词（轻量，由 ticker 周期调用） */
    private fun refreshProgress() {
        val c = controller ?: return
        val dur = c.duration
        if (dur > 0 && !userSeeking) {
            seekBar.progress = (c.currentPosition * 100 / dur).toInt().coerceIn(0, 100)
        }
        tvTimeCur?.text = LyricUtils.formatTime(c.currentPosition)
        tvTimeTot?.text = LyricUtils.formatTime(if (dur > 0) dur else -1)
        // 竖屏播放页进度/时间
        seekBarPage?.let { sb ->
            if (dur > 0 && !userSeeking) {
                sb.progress = (c.currentPosition * 100 / dur).toInt().coerceIn(0, 100)
            }
        }
        tvPageTimeCur?.text = LyricUtils.formatTime(c.currentPosition)
        tvPageTimeTot?.text = LyricUtils.formatTime(if (dur > 0) dur else -1)
        syncLyrics(c.currentPosition)
    }

    // ---------- 歌词 ----------

    /** 当前曲目变化时拉取并解析同名 .lrc；无本地 .lrc 时在线歌词由 loadCoverFor 采用 */
    private fun loadLrcFor(mediaId: String?) {
        lrcLoadedFor = mediaId
        lrcJob?.cancel()
        lrcLines = emptyList()
        if (mediaId.isNullOrEmpty()) return
        val track = adapter.currentList.firstOrNull { it.path == mediaId }
        if (track == null) {
            // 曲库尚未就绪（旋转重建后 controller 先连上）：保持 lrcLoadedFor=mediaId 防止
            // updatePlayerUi 每次重试，标记 lrcDeferred 待列表就绪后由 loadCachedTracks 重试
            lrcDeferred = mediaId
            return
        }
        lrcDeferred = null
        val lrcPath = track.lrcPath
        if (lrcPath == null) {
            android.util.Log.d("LrcDebug", "loadLrcFor: no local .lrc for mediaId=$mediaId, lrcLoadedFor kept=$lrcLoadedFor, online lyrics will be adopted by loadCoverFor")
            // 无同名 .lrc：在线歌词由 loadCoverFor 获取并采用，需保持 lrcLoadedFor == mediaId
            return
        }
        if (!WebDavPrefs.isConfigured(this)) return
        lrcJob = lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    WebDavClient(
                        WebDavPrefs.getUrl(this@MainActivity),
                        WebDavPrefs.getUser(this@MainActivity),
                        WebDavPrefs.getPass(this@MainActivity),
                        WebDavPrefs.isTrustSelfSigned(this@MainActivity)
                    ).fetchText(lrcPath)
                } catch (_: Exception) { null }
            }
            lrcLines = text?.let { LyricUtils.parseLrc(it) } ?: emptyList()
        }
    }

    /** 当前曲目变化时获取封面：本地缓存 → 网易云 → iTunes 兜底；顺带缓存在线歌词 */
    private fun loadCoverFor(mediaId: String?) {
        coverLoadedFor = mediaId
        metaJob?.cancel()
        preloadJob?.cancel()
        preloadFor = null
        albumArtImage?.visibility = View.GONE
        noteIcon?.visibility = View.VISIBLE
        albumArtImageBig?.visibility = View.GONE
        noteIconBig?.visibility = View.VISIBLE
        if (mediaId.isNullOrEmpty()) return
        metaJob = lifecycleScope.launch {
            val track = adapter.currentList.firstOrNull { it.path == mediaId }
            if (track == null) {
                // 曲库尚未就绪：保持 coverLoadedFor=mediaId 防止重复触发，标记 deferred 待重试
                coverDeferred = mediaId
                return@launch
            }
            coverDeferred = null
            val key = LyricUtils.md5Key(mediaId)
            // 0) 本地无同名 .lrc 时，先读离线歌词缓存
            if (track.lrcPath == null) {
                val lrcFile = java.io.File(lrcCacheDir, "$key.lrc")
                if (lrcFile.exists()) {
                    val text = withContext(Dispatchers.IO) {
                        runCatching { lrcFile.readText() }.getOrNull()
                    }
                    if (!text.isNullOrEmpty() && lrcLoadedFor == mediaId) lrcLines = LyricUtils.parseLrc(text)
                }
            }
            // 1) 本地封面缓存直接用
            val cached = java.io.File(coverCacheDir, "$key.jpg")
            val local = withContext(Dispatchers.IO) {
                if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath) else null
            }
            if (local != null) {
                if (coverLoadedFor == mediaId) showCover(local)
                if (coverLoadedFor == mediaId) preloadNextTrack()
                return@launch
            }
            // 2) 在线获取（带上歌曲时长，lrclib 精确匹配命中率更高）
            val (artist, title) = LyricUtils.songTitleArtist(track.name)
            val q = (if (artist.isEmpty()) title else "$title $artist").trim()
            if (q.isEmpty()) return@launch
            val dur = controller?.duration?.takeIf { it > 0 }?.div(1000) ?: 0L
            val meta = withContext(Dispatchers.IO) { OnlineMeta.fetch(q, dur) }
            if (coverLoadedFor != mediaId) return@launch
            meta.coverBytes?.let { bytes ->
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { cached.writeBytes(bytes) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null && coverLoadedFor == mediaId) showCover(bmp)
            }
            // 3) 本地无同名 .lrc 时采用在线歌词并写入缓存
            val condLrcPathNull = track.lrcPath == null
            val condLrcTextOk = !meta.lrcText.isNullOrEmpty()
            val condLrcLoadedMatch = lrcLoadedFor == mediaId
            android.util.Log.d("LrcDebug", "loadCoverFor: adopt online lrc check — lrcPathNull=$condLrcPathNull, lrcTextOk=$condLrcTextOk (len=${meta.lrcText?.length ?: 0}), lrcLoadedForMatch=$condLrcLoadedMatch (lrcLoadedFor=$lrcLoadedFor, mediaId=$mediaId)")
            if (condLrcPathNull && condLrcTextOk && condLrcLoadedMatch) {
                lrcLines = LyricUtils.parseLrc(meta.lrcText!!)
                android.util.Log.d("LrcDebug", "loadCoverFor: online lrc adopted, parsed ${lrcLines.size} lines")
                withContext(Dispatchers.IO) {
                    runCatching { java.io.File(lrcCacheDir, "$key.lrc").writeText(meta.lrcText) }
                }
            }
            // 当前曲封面+歌词就绪，预加载下一曲
            if (coverLoadedFor == mediaId) preloadNextTrack()
        }
    }

    /** 预加载下一曲的封面+歌词+音频开头到本地缓存（失败静默） */
    private fun preloadNextTrack() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (!WebDavPrefs.isConfigured(this)) return
        if (c.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIdx = c.nextMediaItemIndex
        if (nextIdx < 0 || nextIdx == C.INDEX_UNSET) return
        val nextMediaId = c.getMediaItemAt(nextIdx).mediaId ?: return
        if (nextMediaId == c.currentMediaItem?.mediaId) return
        if (preloadFor == nextMediaId) return
        preloadFor = nextMediaId
        val nextTrack = adapter.currentList.firstOrNull { it.path == nextMediaId } ?: return
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val key = LyricUtils.md5Key(nextMediaId)
                    val coverFile = java.io.File(coverCacheDir, "$key.jpg")
                    val lrcFile = java.io.File(lrcCacheDir, "$key.lrc")
                    // 1) 封面+歌词：一次 fetch 拿两者（仅缺失项才请求）
                    val needCover = !coverFile.exists()
                    val needLrc = nextTrack.lrcPath == null && !lrcFile.exists()
                    if (needCover || needLrc) {
                        val (artist, title) = LyricUtils.songTitleArtist(nextTrack.name)
                        val q = (if (artist.isEmpty()) title else "$title $artist").trim()
                        if (q.isNotEmpty()) {
                            val meta = OnlineMeta.fetch(q, 0L)
                            if (meta.coverBytes != null && needCover) {
                                runCatching { coverFile.writeBytes(meta.coverBytes) }
                            }
                            if (!meta.lrcText.isNullOrEmpty() && needLrc) {
                                runCatching { lrcFile.writeText(meta.lrcText) }
                            }
                        }
                    }
                    // 2) 本地同名 .lrc 预拉
                    if (nextTrack.lrcPath != null && !lrcFile.exists()) {
                        runCatching {
                            val text = WebDavClient(
                                WebDavPrefs.getUrl(this@MainActivity),
                                WebDavPrefs.getUser(this@MainActivity),
                                WebDavPrefs.getPass(this@MainActivity),
                                WebDavPrefs.isTrustSelfSigned(this@MainActivity)
                            ).fetchText(nextTrack.lrcPath)
                            if (!text.isNullOrEmpty()) lrcFile.writeText(text)
                        }
                    }
                    // 3) 音频开头 ~1MB 预写入 SimpleCache
                    runCatching { preloadAudioHead(nextMediaId) }
                }
            }
        }
    }

    /** 通过 CacheDataSource 读取下一曲前 ~1MB 音频写入 SimpleCache */
    @Suppress("DEPRECATION")
    private fun preloadAudioHead(nextMediaId: String) {
        if (!WebDavPrefs.isCacheEnabled(this)) return
        val cache = try { MediaCache.get(this) } catch (_: Exception) { return }
        val base = WebDavPrefs.getUrl(this).trimEnd('/')
        val uri = Uri.parse(base + nextMediaId)
        val cacheKey = uri.toString()
        val target = 1L * 1024 * 1024
        if (cache.isCached(cacheKey, 0, target)) return
        val upstream = WebDavDataSourceFactory.createFromPrefs(this)
        val cacheDs = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val dataSpec = DataSpec(uri, 0, target, null)
        try {
            cacheDs.open(dataSpec)
            val buf = ByteArray(8 * 1024)
            var read = 0L
            while (read < target) {
                val want = minOf(buf.size.toLong(), target - read).toInt()
                val n = cacheDs.read(buf, 0, want)
                if (n <= 0) break
                read += n
            }
        } catch (_: Exception) {
        } finally {
            runCatching { cacheDs.close() }
        }
    }

    /** 显示在线封面，隐藏占位音符（横屏面板 + 竖屏播放页） */
    private fun showCover(bmp: android.graphics.Bitmap) {
        albumArtImage?.setImageBitmap(bmp)
        albumArtImage?.visibility = View.VISIBLE
        noteIcon?.visibility = View.GONE
        albumArtImageBig?.setImageBitmap(bmp)
        albumArtImageBig?.visibility = View.VISIBLE
        noteIconBig?.visibility = View.GONE
    }

    /** 按播放位置刷新歌词：横屏三行 + 竖屏播放页当前句，支持逐字高亮 */
    private fun syncLyrics(posMs: Long) {
        val prev = tvLyricPrev
        val cur = tvLyricCur
        val next = tvLyricNext
        val page = tvPageLyric
        if (prev == null && cur == null && next == null && page == null) return
        if (lrcLines.isEmpty()) {
            val noLrc = getString(R.string.no_lyrics)
            if (cur?.text != noLrc) {
                prev?.text = ""
                cur?.text = noLrc
                next?.text = ""
                page?.text = noLrc
            }
            return
        }
        var idx = -1
        for (i in lrcLines.indices) {
            if (lrcLines[i].lineStartMs <= posMs) idx = i else break
        }
        val curLine = if (idx >= 0) lrcLines[idx] else null
        prev?.text = if (idx > 0) lrcLines[idx - 1].text else ""
        next?.text = if (idx + 1 < lrcLines.size) lrcLines[idx + 1].text else ""

        if (curLine == null) {
            cur?.text = ""
            page?.text = ""
            return
        }

        // 逐字歌词：按字高亮已唱部分；逐行歌词：整行高亮
        if (curLine.words.isNotEmpty()) {
            val spannable = buildWordHighlight(curLine, posMs)
            cur?.text = spannable
            page?.text = spannable
        } else {
            cur?.text = curLine.text
            page?.text = curLine.text
        }
    }

    /** 为逐字歌词行构建 SpannableString：已唱部分白色，未唱部分半透明灰色 */
    private fun buildWordHighlight(line: LyricUtils.LyricLine, posMs: Long): android.text.SpannableString {
        val sb = android.text.SpannableString(line.text)
        val white = android.graphics.Color.WHITE
        val dim = android.graphics.Color.argb(110, 255, 255, 255)
        // 默认全部未唱
        sb.setSpan(android.text.style.ForegroundColorSpan(dim), 0, line.text.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        // 找到已唱到的字索引（最后一个 startMs <= posMs 的字）
        var highlightEnd = 0
        for (w in line.words) {
            if (w.startMs <= posMs) {
                highlightEnd += w.text.length
            } else break
        }
        if (highlightEnd > 0) {
            sb.setSpan(android.text.style.ForegroundColorSpan(white), 0, highlightEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ---------- 收藏 ----------

    private fun favPaths(): MutableSet<String> =
        favPrefs.getStringSet("paths", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun updateFavIcon(mediaId: String?) {
        val btn = btnFavorite ?: return
        val fav = !mediaId.isNullOrEmpty() &&
            favPrefs.getStringSet("paths", emptySet())?.contains(mediaId) == true
        btn.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
    }

    private fun toggleFavorite() {
        val mediaId = controller?.currentMediaItem?.mediaId ?: return
        val set = favPaths()
        val added = set.add(mediaId)
        if (!added) set.remove(mediaId)
        favPrefs.edit().putStringSet("paths", set).apply()
        updateFavIcon(mediaId)
        val name = controller?.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        Toast.makeText(this,
            if (added) getString(R.string.toast_favorited, LyricUtils.displayTitle(name)) else getString(R.string.toast_unfavorited),
            Toast.LENGTH_SHORT).show()
    }

    // ---------- 自定义背景图 ----------

    /** 从系统文件选择器导入背景图，复制到内部存储后立即应用 */
    private fun importBg(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        bgFile.outputStream().use { output -> input.copyTo(output) }
                    } != null
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                applyBg()
                Toast.makeText(this@MainActivity, R.string.toast_bg_updated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.toast_bg_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 应用自定义背景图（无则隐藏，露出默认渐变）；竖屏播放页背景同步 */
    private fun applyBg() {
        val iv = bgImage ?: return
        if (!bgFile.exists()) {
            iv.visibility = View.GONE
            bgImagePage?.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
            }
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
                bgImagePage?.setImageBitmap(bmp)
                bgImagePage?.visibility = View.VISIBLE
            }
        }
    }

    // ---------- 列表同步 ----------

    /** 高亮正在播放的曲目并滚动到可见位置（曲目变化时才滚动） */
    private fun syncListHighlight(mediaId: String?) {
        if (mediaId == adapter.currentPath) return
        adapter.setCurrentPath(mediaId)
        queueAdapter?.setCurrentPath(mediaId)
        if (mediaId.isNullOrEmpty()) return
        val idx = adapter.currentList.indexOfFirst { it.path == mediaId }
        if (idx >= 0) {
            // 旋转重建后首次调用时列表可能还没完成布局（height=0），post 到布局完成后再滚
            recyclerView.post {
                if (recyclerView.height > 0) {
                    (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, recyclerView.height / 4)
                } else {
                    recyclerView.scrollToPosition(idx)
                }
            }
        }
        // 播放列表面板打开时同步滚动
        if (queuePanel?.visibility == View.VISIBLE) {
            val ql = findViewById<RecyclerView?>(R.id.queueList)
            val qidx = queueAdapter?.currentList?.indexOfFirst { it.path == mediaId } ?: -1
            if (ql != null && qidx >= 0 && ql.height > 0) {
                (ql.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(qidx, ql.height / 4)
            }
        }
    }

    // ---------- 播放模式 ----------

    /** 循环切换：顺序 → 随机 → 单曲循环 → 列表循环 */
    private fun cyclePlayMode() {
        playMode = (playMode + 1) % PLAY_MODE_NAMES.size
        themePrefs.edit().putInt("play_mode", playMode).apply()
        updatePlayModeIcon()
        controller?.let { applyPlayMode(it) }
        Toast.makeText(this, PLAY_MODE_NAMES[playMode], Toast.LENGTH_SHORT).show()
    }

    /** 把当前模式落到 player（shuffle + repeatMode） */
    private fun applyPlayMode(c: Player) {
        when (playMode) {
            1 -> { c.shuffleModeEnabled = true; c.repeatMode = Player.REPEAT_MODE_OFF }
            2 -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ONE }
            3 -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ALL }
            else -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF }
        }
    }

    private fun updatePlayModeIcon() {
        val res = when (playMode) {
            1 -> R.drawable.ic_shuffle
            2 -> R.drawable.ic_repeat_one
            3 -> R.drawable.ic_repeat
            else -> R.drawable.ic_mode_order
        }
        btnPlayMode?.setImageResource(res)
        btnPageMode?.setImageResource(res)
    }

    // ---------- 竖屏全屏播放页 ----------

    /** 绑定竖屏播放页与播放列表弹层（横屏布局无这些 id，findViewById 返回 null 直接跳过） */
    private fun setupPlayerPage() {
        if (findViewById<View?>(R.id.playerPage) == null) return
        playerPage = findViewById(R.id.playerPage)
        bgImagePage = findViewById(R.id.bgImagePage)
        tvPageTitle = findViewById(R.id.tvPageTitle)
        tvPageTitle?.isSelected = true
        tvPageArtist = findViewById(R.id.tvPageArtist)
        tvPageLyric = findViewById(R.id.tvPageLyric)
        seekBarPage = findViewById(R.id.seekBarPage)
        tvPageTimeCur = findViewById(R.id.tvPageTimeCur)
        tvPageTimeTot = findViewById(R.id.tvPageTimeTot)
        btnPageMode = findViewById(R.id.btnPageMode)
        btnPagePlay = findViewById(R.id.btnPagePlay)
        albumArtImageBig = findViewById(R.id.albumArtImageBig)
        noteIconBig = findViewById(R.id.noteIconBig)
        // 在线封面按占位圆形裁剪（clipToOutline 依赖背景 outline）
        albumArtImageBig?.clipToOutline = true
        // 点底部播放栏展开播放页，左上角箭头返回主页
        playerBar.setOnClickListener { playerPage?.visibility = View.VISIBLE }
        findViewById<View?>(R.id.btnBack)?.setOnClickListener {
            playerPage?.visibility = View.GONE
        }
        btnPageMode?.setOnClickListener { cyclePlayMode() }
        btnPagePlay?.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        findViewById<View?>(R.id.btnPagePrev)?.setOnClickListener { controller?.seekToPreviousMediaItem() }
        findViewById<View?>(R.id.btnPageNext)?.setOnClickListener { controller?.seekToNext() }
        seekBarPage?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                val dur = c.duration
                if (dur > 0) c.seekTo((sb?.progress ?: 0).toLong() * dur / 100)
            }
        })
        // 播放列表弹层（参考图二：仅「正在播放」）
        queuePanel = findViewById(R.id.queuePanel)
        tvQueueTitle = findViewById(R.id.tvQueueTitle)
        findViewById<View?>(R.id.btnPageQueue)?.setOnClickListener { openQueuePanel() }
        // 主页播放栏的播放列表按钮（竖屏），同弹一个面板
        findViewById<View?>(R.id.btnBarQueue)?.setOnClickListener { openQueuePanel() }
        findViewById<View?>(R.id.queueScrim)?.setOnClickListener {
            hideQueuePanel()
        }
        findViewById<View?>(R.id.btnQueueClose)?.setOnClickListener {
            hideQueuePanel()
        }
        val list = findViewById<RecyclerView?>(R.id.queueList)
        if (list != null) {
            val qa = TrackAdapter()
            qa.dark = true
            qa.onItemClick = { track -> playTrackFromList(track) }
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = qa
            queueAdapter = qa
        }
    }

    /** 打开播放列表面板：灌入当前曲库列表并定位到正在播放 */
    private fun openQueuePanel() {
        val qa = queueAdapter ?: return
        qa.submitList(adapter.currentList)
        tvQueueTitle?.text = getString(R.string.queue_playing_count, adapter.itemCount)
        val mediaId = controller?.currentMediaItem?.mediaId
        qa.setCurrentPath(mediaId)
        showQueuePanel()
        if (!mediaId.isNullOrEmpty()) {
            val list = findViewById<RecyclerView?>(R.id.queueList) ?: return
            list.post {
                val idx = qa.currentList.indexOfFirst { it.path == mediaId }
                if (idx >= 0 && list.height > 0) {
                    (list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, list.height / 4)
                }
            }
        }
    }

    /** 强制把列表滚动到正在播放的曲目（绕过高亮去重；旋转重建后必须重新定位） */
    private fun scrollToCurrent() {
        val mediaId = controller?.currentMediaItem?.mediaId ?: return
        val idx = adapter.currentList.indexOfFirst { it.path == mediaId }
        if (idx < 0) return
        recyclerView.post {
            if (recyclerView.height > 0) {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(idx, recyclerView.height / 4)
            } else {
                recyclerView.scrollToPosition(idx)
            }
        }
    }

    /** 面板滑入：遮罩淡入 + 卡片从底部弹起 */
    private fun showQueuePanel() {
        val panel = queuePanel ?: return
        val card = findViewById<View?>(R.id.queueCard)
        panel.animate().cancel()
        card?.animate()?.cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(200).start()
        card?.let {
            it.translationY = (if (it.height > 0) it.height else 480).toFloat()
            it.animate().translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /** 面板滑出：遮罩淡出 + 卡片沉回底部，动画结束后隐藏 */
    private fun hideQueuePanel() {
        val panel = queuePanel ?: return
        if (panel.visibility != View.VISIBLE) return
        val card = findViewById<View?>(R.id.queueCard)
        panel.animate().cancel()
        card?.animate()?.cancel()
        panel.animate().alpha(0f).setDuration(180).start()
        card?.animate()?.translationY((if (card.height > 0) card.height else 480).toFloat())
            ?.setDuration(220)
            ?.withEndAction {
                panel.visibility = View.GONE
                panel.alpha = 1f
                card.translationY = 0f
            }
            ?.start()
    }

    // ---------- 主题配色 ----------

    private fun showThemeDialog() {
        // 自定义白字 item 布局：保证在任何主题（包括雅致黑）渐变底上都清晰可读
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, R.layout.item_theme_option, THEME_NAMES
        ) {}
        AlertDialog.Builder(this, R.style.ThemeOverlay_WebDavPlayer_Alert)
            .setTitle(R.string.dialog_title_theme)
            .setSingleChoiceItems(adapter, currentTheme) { d, which ->
                currentTheme = which
                themePrefs.edit().putInt("theme", which).apply()
                applyTheme()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
            .apply {
                window?.setBackgroundDrawable(themeDialogBg())
                show()
            }
    }

    /** 对话框背景：当前主题三色渐变 + 20dp 圆角 */
    private fun themeDialogBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            colors = THEME_GRADS[currentTheme]
            cornerRadius = 20f * resources.displayMetrics.density
        }

    /** 全局崩溃捕获：堆栈写 /sdcard/Android/data/com.webdav.music/files/crash/，然后交给系统默认处理 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashSaver) return
        Thread.setDefaultUncaughtExceptionHandler(CrashSaver(this, prev))
    }

    private class CrashSaver(
        val ctx: android.content.Context,
        val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val dir = java.io.File(ctx.getExternalFilesDir(null), "crash")
                dir.mkdirs()
                java.io.File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "thread=${t.name}\n${android.util.Log.getStackTraceString(e)}"
                )
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * 控件尺寸随屏幕分辨率自适应（方向感知）：
     *  - 竖屏：短边恒等于 360dp（1080x2340 手机 → density 3.0）
     *  - 横屏：高度恒等于 480dp（1280x720 车机 → density 1.5 → 逻辑 853x480dp；
     *    横屏右侧播放面板内容约 430dp，若按短边/360 会导致逻辑高度 360dp 装不下）
     */
    private fun adaptDisplayDensity() {
        val dm = resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        val newDensity = if (portrait) {
            minOf(dm.widthPixels, dm.heightPixels) / 360f
        } else {
            dm.heightPixels / 480f
        }
        if (kotlin.math.abs(newDensity - dm.density) > 0.01f) {
            dm.density = newDensity
            dm.densityDpi = (newDensity * 160).toInt()
            dm.scaledDensity = newDensity * resources.configuration.fontScale
        }
    }

    private fun applyTheme() {
        currentTheme = themePrefs.getInt("theme", 0).coerceIn(0, THEME_NAMES.size - 1)
        val grad = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            THEME_GRADS[currentTheme])
        // 窗口背景也用主题渐变：刘海/挖孔区不会露出白色
        window.setBackgroundDrawable(grad)
        findViewById<View?>(R.id.rootContainer)?.background = grad
        // 竖屏播放页背景同步主题渐变
        findViewById<View?>(R.id.pageBg)?.background = grad
        // 封面占位用 强调色→浅强调色 渐变，与根背景区分；20dp 圆角方形
        val artGrad = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(THEME_ACCENTS[currentTheme], THEME_ACCENT_LIGHTS[currentTheme]))
        artGrad.cornerRadius = 20f * resources.displayMetrics.density
        findViewById<View?>(R.id.albumArtView)?.background = artGrad
        // 竖屏播放页大封面同步
        findViewById<View?>(R.id.albumArtBig)?.background = artGrad
        // 播放列表弹窗跟随主题渐变（顶部圆角）
        findViewById<View?>(R.id.queueCard)?.let { card ->
            val d = android.graphics.drawable.GradientDrawable()
            d.orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            d.colors = THEME_GRADS[currentTheme]
            val r = 22f * resources.displayMetrics.density
            d.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            card.background = d
        }
        // 已打开的设置对话框同步刷新
        settingsDialog?.takeIf { it.isShowing }?.window?.setBackgroundDrawable(themeDialogBg())
        adapter.accentColor = THEME_ACCENT_LIGHTS[currentTheme]
    }

    companion object {
        private val THEME_GRADS = arrayOf(
            intArrayOf(0xFF39479B.toInt(), 0xFF7A4A7C.toInt(), 0xFFB04A38.toInt()),
            intArrayOf(0xFF0B3B2C.toInt(), 0xFF15803D.toInt(), 0xFF65A30D.toInt()),
            intArrayOf(0xFF3B0D0D.toInt(), 0xFF7F1D1D.toInt(), 0xFFDC2626.toInt()),
            intArrayOf(0xFF0C2A52.toInt(), 0xFF1D4ED8.toInt(), 0xFF38BDF8.toInt()),
            intArrayOf(0xFF451A03.toInt(), 0xFFC2410C.toInt(), 0xFFFBBF24.toInt()),
            intArrayOf(0xFFA9AAB8.toInt(), 0xFF9395A6.toInt(), 0xFF7E8093.toInt()),
            intArrayOf(0xFF9A9AA6.toInt(), 0xFF75757F.toInt(), 0xFF55555E.toInt()),
            intArrayOf(0xFF0C0C10.toInt(), 0xFF17171D.toInt(), 0xFF26262E.toInt()))
        private val THEME_ACCENTS = intArrayOf(
            0xFF6C5CE7.toInt(), 0xFF1DB954.toInt(), 0xFFDD001B.toInt(),
            0xFF3B82F6.toInt(), 0xFFF59E0B.toInt(), 0xFF8E7CF3.toInt(),
            0xFF03DAC5.toInt(), 0xFF03DAC5.toInt())
        private val THEME_ACCENT_LIGHTS = intArrayOf(
            0xFFA29BFE.toInt(), 0xFF4ADE80.toInt(), 0xFFF87171.toInt(),
            0xFF7DD3FC.toInt(), 0xFFFCD34D.toInt(), 0xFFC4B8FD.toInt(),
            0xFF7DF5EA.toInt(), 0xFF7DF5EA.toInt())
    }
}
