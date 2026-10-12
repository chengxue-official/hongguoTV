package com.houg.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewParent
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.net.NetClient
import com.houg.tv.playback.CencMp4Downloader
import com.houg.tv.playback.CencMp4Parser
import com.houg.tv.playback.CencMp4Remuxer
import com.houg.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播放器：ExoPlayer（media3），下载→CENC 解密去封装→播本地明文 mp4（HEVC hvc1 + AAC）。
 * 播放栏对齐 Flutter 版：进度条 + 播放/暂停 + 上一集/下一集 + 返回。
 */
class PlayerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var seriesId: String
    private lateinit var title: String
    private lateinit var cover: String
    private var vidList: List<String> = emptyList()
    private var epList: List<Int> = emptyList()
    /** 当前位置下标（0-based）：在 vidList/epList 间移动；显示集号取 epList[位置] */
    private var currentPos = 0
    private var playJob: Job? = null
    private var playGen = 0

    private var player: ExoPlayer? = null
    private var tracking = false
    /** 切后台前记录的播放进度（ms），恢复时 seek 到该位置 */
    private var resumePosition = 0

    /** 视频真实宽高（onVideoSizeChanged 回调，未解析前为 0） */
    private var videoWidth = 0
    private var videoHeight = 0
    /** 旋转角与像素宽高比：竖屏短剧常带 90° 旋转元数据，不处理会横向铺满（人被拉胖） */
    private var videoRotation = 0
    private var pixelRatio = 1f
    /** 从 mp4 tkhd 解析的显示宽高与旋转角（prepareClearFile 后设置，优先于 ExoPlayer 回调） */
    private var tkhdWidth = 0
    private var tkhdHeight = 0
    private var tkhdRotation = 0
    /** 当前画幅模式下标，见 ASPECT_MODES */
    private var aspectMode = 0

    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnAspect: TextView
    private lateinit var episodeInfo: TextView
    private lateinit var progress: View
    private lateinit var errorText: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnDanmaku: TextView
    private lateinit var danmakuView: DanmakuView
    private var speedIndex = 2
    private var danmakuEnabled = true
    private val danmakuFetchedWindows = mutableSetOf<Long>()
    private var lastDanmakuWindow = -1L
    /** 预加载：已发起预取的下一集位置与任务句柄 */
    private var prefetchedPos = -1
    private var prefetchJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEpisodeComplete()
        }

        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            videoWidth = size.width
            videoHeight = size.height
            // tkhd 解析结果优先：竖屏短剧带 90° 旋转元数据时 ExoPlayer 回调的
            // width/height 已是旋转后的（部分老 ROM 解码器不旋转），但保险起见
            // 以 mp4 tkhd 的显示宽高为准
            if (tkhdWidth > 0 && tkhdHeight > 0) {
                if (tkhdRotation == 90 || tkhdRotation == 270) {
                    // 竖屏视频：显示宽高互换后仍是竖条，直接用
                    videoWidth = tkhdWidth
                    videoHeight = tkhdHeight
                } else {
                    videoWidth = tkhdWidth
                    videoHeight = tkhdHeight
                }
            }
            applyAspectRatio()
        }

        override fun onPlayerError(error: PlaybackException) {
            progress.visibility = View.GONE
            showError("当前设备可能不支持 H.265 解码（" + error.errorCodeName + "）")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        vidList = intent.getStringArrayListExtra(EXTRA_VID_LIST) ?: emptyList()
        epList = intent.getIntegerArrayListExtra(EXTRA_EP_LIST) ?: emptyList()
        currentPos = intent.getIntExtra(EXTRA_EPISODE_POS, 0)
            .coerceIn(0, (vidList.size - 1).coerceAtLeast(0))

        surfaceView = findViewById(R.id.video_surface)
        videoContainer = findViewById(R.id.video_container)
        controlBar = findViewById(R.id.control_bar)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnAspect = findViewById(R.id.btn_aspect)
        episodeInfo = findViewById(R.id.episode_info)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.error_text)
        btnSpeed = findViewById(R.id.btn_speed)
        btnDanmaku = findViewById(R.id.btn_danmaku)
        danmakuView = findViewById(R.id.danmaku_view)

        // 读取持久化的画幅模式（默认「适应」= 保持比例）、倍速、弹幕开关
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        aspectMode = prefs.getInt(PREF_ASPECT, ASPECT_FIT)
            .coerceIn(0, ASPECT_MODES.size - 1)
        speedIndex = prefs.getInt(PREF_SPEED, 2).coerceIn(0, SPEEDS.size - 1)
        danmakuEnabled = prefs.getBoolean(PREF_DANMAKU, true)

        player = ExoPlayer.Builder(this).build()
        player!!.setVideoSurfaceView(surfaceView)
        player!!.setAudioAttributes(AudioAttributes.DEFAULT, /*handleAudioFocus=*/true)
        player!!.addListener(playerListener)

        updateAspectLabel()
        updateSpeedLabel()
        updateDanmakuLabel()
        applyDanmakuVisibility()
        updateEpisodeInfo()
        bindControls()

        // 首次显示播放栏，3 秒后隐藏；ExoPlayer 自管 surface，直接起播
        showControls()
        handler.post(progressRunnable)
        playCurrent()
    }

    private fun bindControls() {
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { switchEpisode(currentPos - 1) }
        btnNext.setOnClickListener { switchEpisode(currentPos + 1) }
        findViewById<View>(R.id.btn_exit).setOnClickListener { finish() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnSpeed.setOnClickListener { cycleSpeed() }
        btnDanmaku.setOnClickListener { toggleDanmaku() }

        surfaceView.setOnClickListener { showControls() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val exo = player ?: return
                    val dur = exo.duration
                    if (dur > 0) exo.seekTo(p.toLong() * dur / 1000)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                tracking = false
                showControls()
            }
        })
    }

    private fun playCurrent() {
        if (vidList.isEmpty()) {
            finish()
            return
        }
        if (currentPos < 0 || currentPos >= vidList.size) {
            finish()
            return
        }
        val vid = vidList[currentPos]

        // 取消上一次未完成的取流/解密请求，避免旧响应晚到覆盖新集
        playJob?.cancel()
        playGen++
        val gen = playGen

        // 切集/起播时清空弹幕缓存、画幅元数据与预加载状态（下一集可能横竖屏不同）
        danmakuFetchedWindows.clear()
        lastDanmakuWindow = -1L
        danmakuView.reset()
        tkhdWidth = 0; tkhdHeight = 0; tkhdRotation = 0
        videoWidth = 0; videoHeight = 0
        prefetchedPos = -1

        updateEpisodeInfo()
        progress.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        playJob = scope.launch {
            try {
                // 预加载命中：本地已有明文 mp4，直接起播，跳过取流+下载+解密（消除切集卡顿）
                val cached = withContext(Dispatchers.IO) { findCachedClearFile(vid) }
                if (cached != null) {
                    if (gen != playGen) return@launch
                    AppLog.d("PlayerActivity", "命中预加载缓存 vid=$vid")
                    parseTkhd(cached.readBytes())
                    startPlayback(cached)
                    return@launch
                }
                val video = withContext(Dispatchers.IO) {
                    App.netClient.videoModel(vid)
                }
                if (gen != playGen) return@launch
                val clearFile = withContext(Dispatchers.IO) { prepareClearFile(video) }
                if (gen != playGen) return@launch
                startPlayback(clearFile)
            } catch (e: Exception) {
                if (gen != playGen) return@launch
                progress.visibility = View.GONE
                showError(e.message ?: getString(R.string.load_failed))
            }
        }
    }

    /** 查找该 vid 已预加载的明文缓存文件（存在且非空才算命中）。 */
    private fun findCachedClearFile(vid: String): File? {
        return try {
            val f = File(cacheDir, "clear_$vid.mp4")
            if (f.exists() && f.length() > 1024) f else null
        } catch (e: Exception) {
            null
        }
    }

    /** 下载 → CENC 解密去封装 → 写本地明文 mp4，返回文件。运行在 IO 线程。 */
    private fun prepareClearFile(video: NetClient.VideoModel): File {
        val downloader = CencMp4Downloader(HttpClientProvider.get(this))
        val variants = video.variants

        if (variants.isNotEmpty()) {
            for (v in variants) {
                if (!v.encrypted) continue
                try {
                    val t0 = System.currentTimeMillis()
                    val enc = downloader.downloadWithRetry(v.urls)
                    val clear = CencMp4Remuxer.remux(enc, v.cencKey)
                    parseTkhd(clear)
                    val file = File(cacheDir, "clear_" + video.videoId + ".mp4")
                    file.writeBytes(clear)
                    AppLog.d("PlayerActivity", "prepareClearFile codec=" + v.codec
                            + " height=" + v.height + " enc=" + enc.size
                            + " clear=" + clear.size + " 耗时(ms)=" + (System.currentTimeMillis() - t0)
                            + " tkhd=" + tkhdWidth + "x" + tkhdHeight + " rot=" + tkhdRotation)
                    return file
                } catch (e: Exception) {
                    AppLog.e("PlayerActivity", "变体下载/解密失败", e)
                }
            }
        } else {
            // 向后兼容：variants 为空时退回 urls + cencKey
            try {
                val enc = downloader.downloadWithRetry(video.urls)
                val clear = CencMp4Remuxer.remux(enc, video.cencKey)
                parseTkhd(clear)
                val file = File(cacheDir, "clear_" + video.videoId + ".mp4")
                file.writeBytes(clear)
                return file
            } catch (e: Exception) {
                AppLog.e("PlayerActivity", "下载/解密失败", e)
            }
        }
        throw Exception("视频下载或解密失败")
    }

    /** 从解密后的 mp4 解析 tkhd 显示宽高与旋转角（用于竖屏画幅适配）。 */
    private fun parseTkhd(clear: ByteArray) {
        try {
            val top = CencMp4Parser.parse(clear, 0, clear.size)
            for (trak in CencMp4Parser.findRecursive(top, "trak")) {
                val g = CencMp4Parser.displayGeometry(clear, trak)
                // 只取视频轨（宽高非 0 的那条；音频轨宽高为 0）
                if (g.width > 0 && g.height > 0) {
                    tkhdWidth = g.width
                    tkhdHeight = g.height
                    tkhdRotation = g.rotation
                    return
                }
            }
        } catch (e: Exception) {
            AppLog.d("PlayerActivity", "tkhd 解析失败: " + (e.message ?: ""))
        }
    }

    private fun startPlayback(file: File) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        applySpeed()
        p.playWhenReady = true
        if (resumePosition > 0) {
            p.seekTo(resumePosition.toLong())
            resumePosition = 0
        }
        progress.visibility = View.GONE
        updatePlayPauseButton()
        recordHistory()
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseButton()
        showControls()
    }

    /** 循环切换画幅模式并持久化。 */
    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % ASPECT_MODES.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, aspectMode).apply()
        updateAspectLabel()
        applyAspectRatio()
        showControls()
    }

    /** 更新画幅按钮文案为「画幅·模式名」。 */
    private fun updateAspectLabel() {
        btnAspect.text = getString(R.string.aspect_ratio) + "·" + getString(ASPECT_MODES[aspectMode].labelRes)
    }

    /** 循环切换倍速档位并持久化。 */
    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SPEED, speedIndex).apply()
        updateSpeedLabel()
        applySpeed()
        showControls()
    }

    /** 将当前倍速应用到播放器与弹幕时钟。 */
    private fun applySpeed() {
        player?.setPlaybackParameters(PlaybackParameters(SPEEDS[speedIndex]))
        danmakuView.setRate(SPEEDS[speedIndex])
    }

    private fun updateSpeedLabel() {
        btnSpeed.text = "倍速·" + SPEEDS[speedIndex].toString() + "x"
    }

    /** 切换弹幕开关并持久化。 */
    private fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DANMAKU, danmakuEnabled).apply()
        updateDanmakuLabel()
        applyDanmakuVisibility()
        showControls()
    }

    private fun updateDanmakuLabel() {
        btnDanmaku.text = if (danmakuEnabled) "弹幕·开" else "弹幕·关"
    }

    private fun applyDanmakuVisibility() {
        danmakuView.visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
    }

    /** 每个 500ms 轮询里更新弹幕时钟，并在跨入新的 30s 窗口时触发拉取。 */
    private fun updateDanmaku(p: ExoPlayer, pos: Long, dur: Long) {
        if (!danmakuEnabled) return
        danmakuView.setPositionMs(pos)
        danmakuView.setPlaying(p.isPlaying)
        danmakuView.setRate(SPEEDS[speedIndex])
        if (dur <= 0) return
        // 播放进度走到 80% 时预加载下一集（取流+下载+解密），消除切集卡顿
        if (dur > 0 && pos >= dur * 4 / 5) prefetchNext()
        val window = pos / DANMAKU_WINDOW_MS * DANMAKU_WINDOW_MS
        if (window == lastDanmakuWindow) return
        lastDanmakuWindow = window
        if (danmakuFetchedWindows.contains(window)) return
        fetchDanmaku(window, dur)
    }

    /**
     * 预加载下一集：提前请求 videoModel 并下载解密到本地缓存文件。
     * 切集时若缓存命中则跳过网络直接起播。用 prefetchGen 防重复、防旧任务覆盖。
     */
    private fun prefetchNext() {
        val nextPos = currentPos + 1
        if (nextPos >= vidList.size) return
        if (prefetchedPos == nextPos) return
        if (prefetchJob?.isActive == true) return
        prefetchedPos = nextPos
        val vid = vidList[nextPos]
        val gen = playGen
        prefetchJob = scope.launch {
            try {
                AppLog.d("PlayerActivity", "预加载下一集 pos=$nextPos vid=$vid")
                val video = withContext(Dispatchers.IO) { App.netClient.videoModel(vid) }
                val file = withContext(Dispatchers.IO) {
                    val downloader = CencMp4Downloader(HttpClientProvider.get(this@PlayerActivity))
                    var out: File? = null
                    if (video.variants.isNotEmpty()) {
                        for (v in video.variants) {
                            if (!v.encrypted) continue
                            try {
                                val clear = CencMp4Remuxer.remux(
                                        downloader.downloadWithRetry(v.urls), v.cencKey)
                                val f = File(cacheDir, "clear_" + video.videoId + ".mp4")
                                f.writeBytes(clear)
                                out = f
                                break
                            } catch (e: Exception) {
                                AppLog.e("PlayerActivity", "预加载变体失败", e)
                            }
                        }
                    }
                    if (out == null && video.urls.isNotEmpty()) {
                        try {
                            val clear = CencMp4Remuxer.remux(
                                    downloader.downloadWithRetry(video.urls), video.cencKey)
                            val f = File(cacheDir, "clear_" + video.videoId + ".mp4")
                            f.writeBytes(clear)
                            out = f
                        } catch (e: Exception) {
                            AppLog.e("PlayerActivity", "预加载兜底失败", e)
                        }
                    }
                    out
                }
                if (gen != playGen) return@launch
                AppLog.d("PlayerActivity", "预加载完成 pos=$nextPos file=${file?.name}")
            } catch (e: Exception) {
                if (gen == playGen) {
                    // 预加载失败不打扰用户，切集时走正常路径重试
                    AppLog.d("PlayerActivity", "预加载失败 pos=$nextPos: " + (e.message ?: ""))
                    prefetchedPos = -1
                }
            }
        }
    }
    private fun fetchDanmaku(window: Long, dur: Long) {
        val vid = vidList.getOrNull(currentPos) ?: return
        val gen = playGen
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    App.netClient.danmaku(seriesId, vid, window, dur)
                }
                if (gen != playGen) return@launch
                if (!danmakuEnabled) return@launch
                danmakuFetchedWindows.add(window)
                danmakuView.addItems(items)  // 累积合并，不覆盖其他窗口数据
                AppLog.d("PlayerActivity", "弹幕 window=$window 返回 ${items.size} 条")
            } catch (e: Exception) {
                AppLog.d("PlayerActivity", "弹幕拉取失败 window=$window: " + (e.message ?: ""))
            }
        }
    }

    /** 按当前模式 + 视频真实比例设置画面缩放。 */
    private fun applyAspectRatio() {
        // 显示比例：竖屏视频（h > w）在横屏 TV 上保持竖条比例，人不会被拉胖
        val displayW = videoWidth.toFloat()
        val displayH = videoHeight.toFloat()
        when (ASPECT_MODES[aspectMode]) {
            AspectMode.FIT -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (displayW > 0 && displayH > 0) {
                    videoContainer.setAspectRatio(displayW / displayH)
                }
            }
            AspectMode.STRETCH -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectMode.ORIGINAL -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            }
            AspectMode.FILL -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                if (displayW > 0 && displayH > 0) {
                    videoContainer.setAspectRatio(displayW / displayH)
                }
            }
        }
    }

    private fun updatePlayPauseButton() {
        val p = player
        btnPlayPause.text = if (p != null && p.isPlaying) "暂停" else "播放"
    }

    private fun switchEpisode(pos: Int) {
        if (pos < 0) return
        if (pos >= vidList.size) {
            finish()
            return
        }
        currentPos = pos
        resumePosition = 0
        // 停止上一集并清理旧的明文临时文件，避免取流窗口期误记旧集进度
        releasePlayer()
        cleanupClearFiles()
        playCurrent()
        showControls()
    }

    private fun onEpisodeComplete() {
        recordHistory()
        if (currentPos + 1 < vidList.size) {
            switchEpisode(currentPos + 1)
        } else {
            finish()
        }
    }

    /** 当前集号：优先取 epList[位置]，列表缺失时退化为「位置 + 1」 */
    private fun currentEpisode(): Int = epList.getOrNull(currentPos) ?: (currentPos + 1)

    private fun recordHistory() {
        if (seriesId.isNotEmpty()) {
            App.watchHistory.record(seriesId, title, cover, currentEpisode())
        }
    }

    private fun updateEpisodeInfo() {
        val total = vidList.size
        val text = if (total > 0) "$title · 第 ${currentEpisode()} / $total 集" else title
        episodeInfo.text = text
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.clearMediaItems()
        }
    }

    /** 删除 cacheDir 下所有 clear_ 前缀的明文临时文件。 */
    private fun cleanupClearFiles() {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("clear_")) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun showError(message: String) {
        errorText.text = getString(R.string.play_error, message)
        errorText.visibility = View.VISIBLE
        showControls()
    }

    private fun showControls() {
        controlBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
        restoreControlFocus()
    }

    /**
     * TV 焦点修复：控制栏 3 秒自动隐藏会把按钮上的焦点一并清掉，之后按方向键
     * 全屏没有任何焦点视图可落脚，导致「光标移不到下边栏、OK 键无效」。
     * 这里在控制栏可见时检查：若当前无焦点（或焦点不在控制栏内），显式把
     * 焦点交给播放/暂停按钮。post 到下一帧，确保 visibility 变更先完成布局。
     */
    private fun restoreControlFocus() {
        val focused = currentFocus
        if (focused == null || !isInsideControlBar(focused)) {
            btnPlayPause.post { btnPlayPause.requestFocus() }
        }
    }

    private fun isInsideControlBar(v: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) {
            if (p === controlBar) return true
            p = p.parent
        }
        return false
    }

    private val hideControlsRunnable = Runnable { controlBar.visibility = View.GONE }

    /** 进度条刷新（500ms） */
    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !tracking) {
                val dur = p.duration
                val pos = p.currentPosition
                if (dur > 0) {
                    seekBar.max = 1000
                    seekBar.progress = (pos * 1000 / dur).toInt()
                }
                updateDanmaku(p, pos, dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 按返回键直接弹「继续播放 / 退出」对话框（对齐原版交互）
                showExitDialog()
                return true
            }
            // 遥控器确定键：直接暂停/继续播放（用户诉求）。焦点在按钮上时由按钮消费，
            // 不会走到这里；只有焦点不在按钮（控制栏隐藏）时才由本方法处理。
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                updatePlayPauseButton()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                updatePlayPauseButton()
                showControls()
                return true
            }
            else -> {
                showControls()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 退出确认对话框。用户反馈：选中进度条后按退出，选"继续播放"后
     * 确定键失灵——根因是对话框关闭后焦点丢失。这里在继续播放后
     * 显式把焦点恢复到播放/暂停按钮。
     *
     * 返回不了首页修复：原设 cancelable(false) 导致部分 TV ROM 上对话框
     * 按钮无初始焦点时，返回键也被吞，用户被困在播放页。改为 cancelable(true)
     * （返回键可关闭对话框并恢复播放）+ onShow 显式聚焦「继续播放」按钮。
     */
    private fun showExitDialog() {
        tracking = false
        val p = player
        val wasPlaying = p?.isPlaying == true
        p?.pause()
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("继续播放还是退出？")
            .setPositiveButton("继续播放") { _, _ ->
                if (wasPlaying) player?.play()
                showControls()
                updatePlayPauseButton()
            }
            .setNegativeButton("退出播放") { _, _ -> finish() }
            .setCancelable(true)
            .create()
        // 返回键关闭对话框 = 取消退出：恢复播放，避免停在暂停态让用户以为卡死
        dialog.setOnCancelListener { if (wasPlaying) player?.play() }
        // TV 上部分 ROM 不给对话框按钮初始焦点，显示后显式聚焦「继续播放」
        dialog.setOnShowListener {
            try {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            } catch (_: Exception) {
            }
        }
        // 关键：对话框真正关闭后再恢复焦点（回调里 requestFocus 会被对话框抢占而失效）
        dialog.setOnDismissListener {
            if (!isFinishing) {
                btnPlayPause.post { btnPlayPause.requestFocus() }
            }
        }
        dialog.show()
    }

    override fun onStop() {
        super.onStop()
        // 用户反馈：按主页返回后后台还在出声音。Activity 不可见时必须暂停
        //（recordHistory 记录进度，回前台 onResume 恢复播放）
        resumePosition = try {
            val p = player
            if (p != null && p.duration > 0) p.currentPosition.toInt() else 0
        } catch (_: Exception) {
            0
        }
        recordHistory()
        player?.pause()
        updatePlayPauseButton()
    }

    override fun onResume() {
        super.onResume()
        // 从后台/按主页返回后回前台：从记录位置续播
        if (resumePosition > 0) {
            try {
                val p = player ?: return
                if (p.duration > 0) p.seekTo(resumePosition.toLong())
            } catch (_: Exception) {
            }
            resumePosition = 0
        }
        if (player != null && player!!.playbackState != Player.STATE_IDLE) {
            player?.play()
            updatePlayPauseButton()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        cleanupClearFiles()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        /** 起始位置下标（0-based），非集号 */
        const val EXTRA_EPISODE_POS = "episode_pos"
        const val EXTRA_VID_LIST = "vid_list"
        /** 与 vid_list 按下标一一对应的集号列表 */
        const val EXTRA_EP_LIST = "ep_list"

        private const val PREFS = "player_prefs"
        private const val PREF_ASPECT = "aspect_mode"
        private const val PREF_SPEED = "playback_speed"
        private const val PREF_DANMAKU = "danmaku_enabled"
        private const val DANMAKU_WINDOW_MS = 30000L

        /** 倍速档位 */
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 画幅模式：适应（默认，保持比例）/ 拉伸 / 原始 / 填充 */
        const val ASPECT_FIT = 0
        const val ASPECT_STRETCH = 1
        const val ASPECT_ORIGINAL = 2
        const val ASPECT_FILL = 3

        private enum class AspectMode(val labelRes: Int) {
            FIT(R.string.aspect_fit),
            STRETCH(R.string.aspect_stretch),
            ORIGINAL(R.string.aspect_original),
            FILL(R.string.aspect_fill)
        }

        private val ASPECT_MODES = AspectMode.values()
    }
}
