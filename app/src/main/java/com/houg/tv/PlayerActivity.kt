package com.houg.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.Lifecycle
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
import java.io.RandomAccessFile
import java.util.Locale

/**
 * 播放器：ExoPlayer（media3），下载→CENC 解密去封装→播本地明文 mp4（HEVC hvc1 + AAC）。
 *
 * 交互（遥控器优先，无底部导航栏）：
 *  - OK 键           ：播放 / 暂停
 *  - 左右键          ：快退 / 快进 15 秒；长按按键重复事件会叠加步长（15s→30s→45s…）
 *  - 上 / 下键       ：上一集 / 下一集
 *  - 菜单键          ：调出菜单（画面填充、弹幕开关、倍速、退出）
 *  - 返回键          ：双击退出（首次提示，2 秒内再按一次退出播放）
 *  - 任意按键        ：短暂显示顶部集信息与底部进度（自动隐藏，不抢焦点）
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
    /** 从 mp4 tkhd 解析的显示宽高与旋转角（解密/缓存命中后设置，优先于 ExoPlayer 回调） */
    private var tkhdWidth = 0
    private var tkhdHeight = 0
    private var tkhdRotation = 0
    /**
     * 应播放状态：区分「用户主动暂停」与「起播过程中被切到后台」。
     * 起播完成时若已不可见则不应 auto-play；回前台时据此恢复，避免把用户
     * 手动暂停的播放强行续起。
     */
    private var shouldBePlaying = true
    /** 当前画幅模式下标，见 ASPECT_MODES */
    private var aspectMode = 0

    /** 本集起播时是否已经做过「续播 seek」（避免重复 seek 打断用户手动拖动） */
    private var resumedThisEpisode = false
    /**
     * 待执行的续播位置（ms）。
     *
     * ⚠️ 续播 seek 必须等 STATE_READY（duration 已知）再执行：prepare() 未完成时
     * duration 返回 C.TIME_UNSET（大负数），此时无法判断「上次位置是否已接近片尾」。
     * 旧实现在这里直接 seek，如果上次停在片尾附近（每集播完都会把片尾位置写进历史），
     * seek 过去立刻抛 STATE_ENDED → 被判"播完"自动跳下一集 → 下一集同样带着片尾
     * 续播点 → 连环跳集（用户反馈的恶性 bug）。
     */
    private var pendingResumeMs = 0L
    /** 正在切集：用于抑制切集瞬间的虚假 STATE_ENDED 回调 */
    private var switchingEpisode = false
    /** 本集起播是否已失败（解码/网络）。失败时上下键仍可切走，但不再自动跳集。 */
    private var currentEpisodeFailed = false
    /**
     * 因「本地缓存文件损坏」已自动删档重下过的 vid。
     * 每个 vid 只自愈一次，避免文件反复下坏时陷入「下载→失败→再下载」死循环。
     */
    private var ioRetriedVid: String? = null
    /**
     * 切集抑制的安全兜底：STATE_READY / onPlayerError 理论上都会解除抑制，
     * 但为了防任何未知的卡死路径（如某些 ROM 回调丢失），切集时同时挂一个
     * 延迟任务，到点强制解除——上下键永不能被锁死。
     */
    private val switchGuardRunnable = Runnable {
        if (switchingEpisode) {
            AppLog.d("PlayerActivity", "切集抑制超时（兜底解除）")
            switchingEpisode = false
        }
    }
    /** 上次写观看历史的时间戳（节流，避免 500ms 一次写盘） */
    private var lastHistoryWrite = 0L
    /** 上次按返回键的时间戳（双击退出判定） */
    private var lastBackPressed = 0L

    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: View
    private lateinit var controlBar: View
    private lateinit var menuPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var episodeInfo: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var toastHint: TextView
    private lateinit var menuFill: TextView
    private lateinit var menuDanmaku: TextView
    private lateinit var menuSpeed: TextView
    private lateinit var menuEpisodes: TextView
    private lateinit var menuExit: TextView
    private lateinit var progress: View
    private lateinit var errorText: TextView
    private lateinit var danmakuView: DanmakuView

    // ===== 选集弹窗 =====
    private lateinit var epPickerRoot: View
    private lateinit var epPickerTitle: TextView
    private lateinit var epPickerRange: TextView
    private lateinit var epGrid: GridLayout
    private lateinit var epBtnClose: TextView
    private lateinit var epBtnJump: TextView
    private lateinit var epBtnPrev: TextView
    private lateinit var epBtnNext: TextView
    /** 弹窗当前页（组）起始集号（0-based，页内第一集） */
    private var epGroupStart = 0
    /** 弹窗内的格子视图缓存（每页 20 个），复用避免反复 new */
    private val epCells = ArrayList<TextView>()

    private var speedIndex = 2
    private var danmakuEnabled = true
    private val danmakuFetchedWindows = mutableSetOf<Long>()
    private var lastDanmakuWindow = -1L
    /**
     * 预加载：串行预取协程句柄 + 运行标记。
     * 从「只预取下一集」升级为「预取当前集之后 PREFETCH_AHEAD 集」，
     * 实现任意方向切集（含选集弹窗跳转）都丝滑命中缓存。
     * "是否已预取" 以本地缓存文件实际存在为准（自校正，不依赖内存集合，
     * 避免清理缓存后内存标记变脏导致漏取）。
     */
    private var prefetchJob: Job? = null
    @Volatile private var prefetchRunning = false
    /** 本轮预取失败的 vid：不再反复重试，避免请求风暴被风控（切集后新窗口自然重来）。 */
    private val failedPrefetch = mutableSetOf<String>()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                // 真正就绪：此刻起才是可信的播放状态，解除切集抑制。
                // 不能提前在 startPlayback() 末尾复位——prepare() 是异步的，
                // 解码器初始化期的 STATE_ENDED 会在那之后才投递过来，抑制窗口太短会漏。
                Player.STATE_READY -> {
                    releaseSwitchGuard()
                    applyPendingResume()
                }
                Player.STATE_ENDED -> onEpisodeComplete()
            }
        }

        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            // tkhd 解析结果优先（解密时已拿到，含旋转元数据）；无 tkhd 时回退 ExoPlayer 回调
            if (tkhdWidth > 0 && tkhdHeight > 0) {
                if (tkhdRotation == 90 || tkhdRotation == 270) {
                    // 竖屏旋转流：tkhd 原始宽高是横向的（如 1920x1080），显示时需互换
                    videoWidth = tkhdHeight
                    videoHeight = tkhdWidth
                } else {
                    videoWidth = tkhdWidth
                    videoHeight = tkhdHeight
                }
            } else {
                videoWidth = size.width
                videoHeight = size.height
            }
            applyAspectRatio()
        }

        override fun onPlayerError(error: PlaybackException) {
            progress.visibility = View.GONE
            // ⚠️ 解码失败的终态是 STATE_IDLE，永远不会再进 STATE_READY。
            // 若不复位切换抑制标记，switchingEpisode 会永久为 true，
            // 用户按上下键切集将被永久拦截（只有退出播放页才能恢复）。
            currentEpisodeFailed = true
            releaseSwitchGuard()

            // ===== 错误分类：I/O 类不是解码问题，别误导用户换设备 =====
            // 线上反馈：VIDAA_TV 报「当前设备可能不支持 H.265 解码
            // (ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)」，但该码属于
            // androidx.media3 的 **IO 分支**（读本地文件越界），与解码器无关。
            // 真因是明文缓存文件损坏/被截断（下载中途进程被杀、磁盘写满等），
            // 而 findCachedClearFile 只看"存在且 >1KB"就会命中坏文件 →
            // 每次进这一集都失败，看起来像"设备不支持"，其实删掉重下即可。
            if (isLocalFileCorruption(error)) {
                val badVid = vidList.getOrNull(currentPos)
                if (badVid != null && badVid != ioRetriedVid) {
                    ioRetriedVid = badVid
                    AppLog.d("PlayerActivity", "疑似本地缓存损坏(" + error.errorCodeName
                            + ")，删除重下 vid=$badVid")
                    try {
                        File(cacheDir, "clear_$badVid.mp4").delete()
                    } catch (e: Throwable) {
                        AppLog.d("PlayerActivity", "删除坏缓存失败: " + (e.message ?: ""))
                    }
                    // 删完直接重新起播本集（playCurrent 会走完整取流+下载+解密路径）。
                    // 每个 vid 只自动重试一次，避免反复失败时陷入下载循环。
                    currentEpisodeFailed = false
                    playCurrent()
                    return
                }
                showError("视频文件异常，请检查设备存储空间后重试（" + error.errorCodeName + "）")
                return
            }
            showError(describePlaybackError(error))
        }
    }

    /**
     * 是否属于「本地明文文件损坏/读取越界」类错误（可通过删缓存重下自愈）。
     * 只认 IO / 容器解析分支；解码器分支交给 describePlaybackError 报 H.265。
     */
    private fun isLocalFileCorruption(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> true
            else -> false
        }
    }

    /** 播放错误 → 用户可读文案（不再把所有错误都甩锅给 H.265）。 */
    private fun describePlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "当前设备可能不支持 H.265 解码（" + error.errorCodeName + "）"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "网络连接中断，请检查网络后重试（" + error.errorCodeName + "）"
            else -> "播放失败（" + error.errorCodeName + "）"
        }
    }

    /** 解除切集抑制（同时撤掉兜底任务）。 */
    private fun releaseSwitchGuard() {
        handler.removeCallbacks(switchGuardRunnable)
        switchingEpisode = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // ===== 保活（用户反馈：看着看着自动进入屏保/电视待机）=====
        // HQQ1 Max 等低内存盒子（总内存 823MB）上，一旦屏保/待机把本 Activity
        // 切到后台，系统立即回收进程（日志证实 19:03:08→19:03:25 进程被杀重启）。
        // ① 窗口常亮 FLAG_KEEP_SCREEN_ON：播放期间系统屏保永不触发（治本）。
        //    该 Flag 只在本窗口前台有效，退出播放页/熄屏由系统自动失效，无需手动管理。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        vidList = intent.getStringArrayListExtra(EXTRA_VID_LIST) ?: emptyList()
        epList = intent.getIntegerArrayListExtra(EXTRA_EP_LIST) ?: emptyList()
        currentPos = intent.getIntExtra(EXTRA_EPISODE_POS, 0)
            .coerceIn(0, (vidList.size - 1).coerceAtLeast(0))

        surfaceView = findViewById(R.id.video_surface)
        videoContainer = findViewById(R.id.video_container)
        topBar = findViewById(R.id.top_bar)
        controlBar = findViewById(R.id.control_bar)
        menuPanel = findViewById(R.id.menu_panel)
        seekBar = findViewById(R.id.seek_bar)
        episodeInfo = findViewById(R.id.episode_info)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        toastHint = findViewById(R.id.toast_hint)
        menuFill = findViewById(R.id.menu_fill)
        menuDanmaku = findViewById(R.id.menu_danmaku)
        menuSpeed = findViewById(R.id.menu_speed)
        menuEpisodes = findViewById(R.id.menu_episodes)
        menuExit = findViewById(R.id.menu_exit)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.error_text)
        danmakuView = findViewById(R.id.danmaku_view)

        epPickerRoot = findViewById(R.id.ep_picker_root)
        epPickerTitle = findViewById(R.id.ep_picker_title)
        epPickerRange = findViewById(R.id.ep_picker_range)
        epGrid = findViewById(R.id.ep_grid)
        epBtnClose = findViewById(R.id.ep_btn_close)
        epBtnJump = findViewById(R.id.ep_btn_jump)
        epBtnPrev = findViewById(R.id.ep_btn_prev)
        epBtnNext = findViewById(R.id.ep_btn_next)

        // 读取持久化的画幅模式（默认「适应」= 保持比例）、倍速、弹幕开关
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        aspectMode = prefs.getInt(PREF_ASPECT, ASPECT_FIT)
            .coerceIn(0, ASPECT_MODES.size - 1)
        speedIndex = prefs.getInt(PREF_SPEED, 2).coerceIn(0, SPEEDS.size - 1)
        danmakuEnabled = prefs.getBoolean(PREF_DANMAKU, true)

        player = ExoPlayer.Builder(this).build()
        player!!.setVideoSurfaceView(surfaceView)
        player!!.setAudioAttributes(AudioAttributes.DEFAULT, /*handleAudioFocus=*/true)
        // ② 播放期间持有 CPU + Wi-Fi 唤醒锁：即便被系统熄屏/待机，缓冲与网络
        //    请求也不会因 CPU 挂起而中断，回前台可无缝续播（配合 FLAG_KEEP_SCREEN_ON，
        //    正常情况下根本不会走到这一步）。需 AndroidManifest 声明 WAKE_LOCK（已有）。
        try {
            player!!.setWakeMode(C.WAKE_MODE_NETWORK)
        } catch (e: Exception) {
            AppLog.d("PlayerActivity", "setWakeMode 失败: " + (e.message ?: ""))
        }
        player!!.addListener(playerListener)

        updateAspectLabel()
        updateSpeedLabel()
        updateDanmakuLabel()
        applyDanmakuVisibility()
        updateEpisodeInfo()
        bindControls()

        showControls()
        // 初始焦点交给隐形锚点：播放页不需要焦点导航，全部按键由 onKeyDown 处理。
        // 若不指定，TV 可能把焦点给到某个可聚焦控件（历史版本曾落在 SeekBar 上，
        // 导致左右键被 SeekBar 吃掉、快进快退失效）。
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
        handler.post(progressRunnable)
        playCurrent()
    }

    private fun bindControls() {
        menuFill.setOnClickListener { cycleAspect() }
        menuDanmaku.setOnClickListener { toggleDanmaku() }
        menuSpeed.setOnClickListener { cycleSpeed() }
        menuEpisodes.setOnClickListener { openEpisodePicker() }
        menuExit.setOnClickListener { showExitDialog() }

        epBtnClose.setOnClickListener { closeEpisodePicker() }
        epBtnJump.setOnClickListener { showJumpDialog() }
        epBtnPrev.setOnClickListener { switchEpGroup(-1) }
        epBtnNext.setOnClickListener { switchEpGroup(1) }

        surfaceView.setOnClickListener { togglePlayPause() }

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
            // 没有可播内容：提示而不是 finish()，避免用户看到"莫名其妙退回选集"
            releaseSwitchGuard()
            showError(getString(R.string.empty))
            return
        }
        if (currentPos < 0 || currentPos >= vidList.size) {
            AppLog.d("PlayerActivity", "playCurrent 越界 pos=$currentPos size=${vidList.size}，忽略")
            releaseSwitchGuard()
            return
        }
        val vid = vidList[currentPos]

        // 取消上一次未完成的取流/解密请求，避免旧响应晚到覆盖新集
        playJob?.cancel()
        playGen++
        val gen = playGen

        // 起播前清掉上一集的失败标记
        currentEpisodeFailed = false

        // 切集时取消在途的预加载任务：它下载的是「按旧的下一集算出的目标」，
        // 继续跑只会白抢带宽（且可能与本集写出同一个文件）。
        // 注意：不删已下载的缓存文件，留给 findCachedClearFile 命中。
        prefetchJob?.cancel()
        prefetchJob = null

        // 切集/起播时清空弹幕缓存与画幅元数据（下一集可能横竖屏不同）
        danmakuFetchedWindows.clear()
        lastDanmakuWindow = -1L
        danmakuView.reset()
        tkhdWidth = 0; tkhdHeight = 0; tkhdRotation = 0
        videoWidth = 0; videoHeight = 0
        resumedThisEpisode = false
        pendingResumeMs = 0L

        updateEpisodeInfo()
        progress.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        // 挂兜底：无论 STATE_READY / onPlayerError 谁先到（或都没到），
        // 到点都强制解除切集抑制，保证上下键永不被锁死。
        handler.removeCallbacks(switchGuardRunnable)
        handler.postDelayed(switchGuardRunnable, SWITCH_GUARD_MS)

        playJob = scope.launch {
            try {
                // 预加载命中：本地已有明文 mp4，直接起播，跳过取流+下载+解密（消除切集卡顿）
                val cached = withContext(Dispatchers.IO) { findCachedClearFile(vid) }
                if (cached != null) {
                    if (gen != playGen) return@launch
                    AppLog.d("PlayerActivity", "命中预加载缓存 vid=$vid")
                    withContext(Dispatchers.IO) { parseTkhd(cached) }
                    if (gen != playGen) return@launch
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
            } catch (e: Throwable) {
                // ⚠️ 必须 catch Throwable 而非 Exception：1.8GB 内存的老设备上
                // remux 瞬时占用可达 ~80MB，触发 OutOfMemoryError（属 Error 不属
                // Exception）。此前漏接导致整个协程崩掉、播放页异常退出。
                if (gen != playGen) return@launch
                AppLog.e("PlayerActivity", "起播失败 vid=$vid", e)
                progress.visibility = View.GONE
                currentEpisodeFailed = true
                // 起播失败也必须解除切集抑制，否则之后所有 STATE_ENDED 都被忽略，
                // 用户按上下键切集将永远没有反应。
                releaseSwitchGuard()
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
                    val file = File(cacheDir, "clear_" + video.videoId + ".mp4")
                    file.writeBytes(clear)
                    parseTkhd(file)
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
                val file = File(cacheDir, "clear_" + video.videoId + ".mp4")
                file.writeBytes(clear)
                parseTkhd(file)
                return file
            } catch (e: Exception) {
                AppLog.e("PlayerActivity", "下载/解密失败", e)
            }
        }
        throw Exception("视频下载或解密失败")
    }

    /**
     * 从解密后的 mp4 解析 tkhd 显示宽高与旋转角（用于竖屏画幅适配）。
     * 只读文件头部（moov 通常在前 256KB 内），不整文件载入内存（单集约 10MB）。
     */
    private fun parseTkhd(clear: File) {
        try {
            val head = ByteArray(MOOV_PROBE_BYTES)
            // read() 单次可能不满读（moov 需 ~104KB），循环读满
            var read = 0
            RandomAccessFile(clear, "r").use { r ->
                while (read < head.size) {
                    val n = r.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
            }
            if (read <= 0) return
            val top = CencMp4Parser.parse(head, 0, read)
            for (trak in CencMp4Parser.findRecursive(top, "trak")) {
                val g = CencMp4Parser.displayGeometry(head, trak)
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
        val p = player
        if (p == null) {
            // Activity 已销毁：解除抑制并按失败处理，避免标记泄漏
            releaseSwitchGuard()
            return
        }
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        applySpeed()

        // 续播：只在「本集尚未续播过」时计算一次待续播位置，真正的 seek 放到
        // STATE_READY（duration 已知）里做——见 applyPendingResume()。
        if (!resumedThisEpisode) {
            resumedThisEpisode = true
            pendingResumeMs = try {
                App.watchHistory.positionOf(seriesId, currentEpisode())
            } catch (_: Exception) {
                0L
            }
        }

        // 关键：起播过程中若已切到后台（用户按了主页），不能 auto-play，否则会
        // 在后台出声——这正是用户反馈的「返回后还有声音」。等回前台再续播。
        if (isVisibleNow()) {
            p.playWhenReady = true
            shouldBePlaying = true
        } else {
            p.playWhenReady = false
            shouldBePlaying = false
        }
        progress.visibility = View.GONE
        recordHistory(force = true)
        // 注意：switchingEpisode 由 playerListener 在收到 STATE_READY 时复位，
        // 覆盖到解码器初始化完成那一刻（比在这里复位更安全）。
    }

    /**
     * STATE_READY 回调里执行待续播 seek。
     *
     * 此时 duration 已可信，可安全判断上界：上次位置若已接近片尾（说明这集其实
     * 已经看完，只是历史里留了个片尾位置），则从头播——否则 seek 过去会立刻
     * STATE_ENDED 并被误判为「播完」而自动跳下一集。
     */
    private fun applyPendingResume() {
        val p = player ?: return
        val saved = pendingResumeMs
        pendingResumeMs = 0L
        if (saved <= RESUME_MIN_MS) return
        val dur = p.duration
        // ⚠️ 片尾判定要用「续播余量」而非「播完容差」：END_TOLERANCE_MS 只有 1.5s，
        // 意味着上次停在「距片尾 4 秒」处仍会续播——可用户刚进去还没来得及看，
        // 4 秒后 STATE_ENDED 就触发自动跳下一集，观感仍是"点进去就跳集"。
        // 短剧单集普遍只有 1~2 分钟，用 15 秒（或时长的 10%，取小）作余量：
        // 剩余不足这么多就认为这集其实已看完，直接从头播。
        val margin = minOf(RESUME_TAIL_MARGIN_MS, dur / 10).coerceAtLeast(END_TOLERANCE_MS)
        if (dur > 0 && saved >= dur - margin) {
            AppLog.d("PlayerActivity", "上次位置接近片尾，从头播 saved=$saved dur=$dur margin=$margin")
            try { p.seekTo(0L) } catch (_: Exception) {}
            return
        }
        try {
            p.seekTo(saved)
        } catch (_: Exception) {
            return
        }
        AppLog.d("PlayerActivity", "续播命中 ep=${currentEpisode()} pos=${saved}ms")
        toast(getString(R.string.resume_hint, currentEpisode().toString(), formatMs(saved)))
    }

    /** Activity 是否处于可见（已开始）状态；onStop 之前视为可见。 */
    private fun isVisibleNow(): Boolean {
        return try {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (_: Exception) {
            true
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        recordHistory(force = true)
        showControls()
    }

    /** 循环切换画幅模式并持久化。 */
    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % ASPECT_MODES.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, aspectMode).apply()
        updateAspectLabel()
        applyAspectRatio()
        toast(getString(R.string.menu_fill) + "：" + getString(ASPECT_MODES[aspectMode].labelRes))
    }

    /** 更新画幅菜单文案为「画面填充：模式名」。 */
    private fun updateAspectLabel() {
        menuFill.text = getString(R.string.menu_fill) + "：" + getString(ASPECT_MODES[aspectMode].labelRes)
    }

    /** 循环切换倍速档位并持久化。 */
    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SPEED, speedIndex).apply()
        updateSpeedLabel()
        applySpeed()
        toast(getString(R.string.menu_speed) + "：" + SPEEDS[speedIndex].toString() + "x")
    }

    /** 将当前倍速应用到播放器与弹幕时钟。 */
    private fun applySpeed() {
        player?.setPlaybackParameters(PlaybackParameters(SPEEDS[speedIndex]))
        danmakuView.setRate(SPEEDS[speedIndex])
    }

    private fun updateSpeedLabel() {
        menuSpeed.text = getString(R.string.menu_speed) + "：" + SPEEDS[speedIndex].toString() + "x"
    }

    /** 切换弹幕开关并持久化。 */
    private fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DANMAKU, danmakuEnabled).apply()
        updateDanmakuLabel()
        applyDanmakuVisibility()
        // 重新打开弹幕时，当前窗口可能已被标记为"已拉取"（当时开关是关的，拉取被
        // 跳过），这里清掉标记让它重新拉一次，否则要等下一个 30s 窗口才出弹幕。
        if (danmakuEnabled) {
            danmakuFetchedWindows.clear()
            lastDanmakuWindow = -1L
        }
        toast(if (danmakuEnabled) getString(R.string.menu_danmaku_on) else getString(R.string.menu_danmaku_off))
    }

    private fun updateDanmakuLabel() {
        menuDanmaku.text = if (danmakuEnabled) getString(R.string.menu_danmaku_on) else getString(R.string.menu_danmaku_off)
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
        val window = pos / DANMAKU_WINDOW_MS * DANMAKU_WINDOW_MS
        if (window == lastDanmakuWindow) return
        lastDanmakuWindow = window
        if (danmakuFetchedWindows.contains(window)) return
        fetchDanmaku(window, dur)
    }

    /**
     * 预加载当前集之后的若干集：提前请求 videoModel 并下载解密到本地缓存文件。
     * 切集（含选集弹窗跳转）时若缓存命中则跳过网络直接起播，实现丝滑切集。
     *
     * 设计要点：
     *  - **串行**下载（一次一集）：并发会抢带宽拖慢当前集，且 remux 瞬时内存
     *    占用叠加，低内存盒子（823MB）容易 OOM。
     *  - **以文件存在为准**判断是否需要预取，自校正、无内存状态漂移。
     *  - 由 500ms 进度回调反复调用，切集后新窗口会自动补上；prefetchRunning
     *    保证同一时刻只有一个预取循环在跑。
     *  - 失败后不立即重试该集（记入 failedPos），避免请求风暴被风控。
     */
    private fun prefetchNext() {
        if (prefetchRunning) return
        // 找出预取窗口内尚未缓存的集（currentPos+1 .. currentPos+PREFETCH_AHEAD）
        val pending = ArrayList<Int>()
        val end = minOf(currentPos + PREFETCH_AHEAD, vidList.size - 1)
        for (p in (currentPos + 1)..end) {
            val vid = vidList.getOrNull(p) ?: continue
            if (vid in failedPrefetch) continue
            if (findCachedClearFile(vid) != null) continue
            pending.add(p)
        }
        if (pending.isEmpty()) return

        prefetchRunning = true
        val gen = playGen
        prefetchJob = scope.launch {
            try {
                for (pos in pending) {
                    if (gen != playGen) return@launch   // 已切集，本窗口作废
                    val vid = vidList.getOrNull(pos) ?: continue
                    if (findCachedClearFile(vid) != null) continue
                    try {
                        AppLog.d("PlayerActivity", "预加载 pos=$pos vid=$vid")
                        val video = withContext(Dispatchers.IO) { App.netClient.videoModel(vid) }
                        withContext(Dispatchers.IO) {
                            val downloader = CencMp4Downloader(
                                    HttpClientProvider.get(this@PlayerActivity))
                            var ok = false
                            if (video.variants.isNotEmpty()) {
                                for (v in video.variants) {
                                    if (!v.encrypted) continue
                                    try {
                                        val clear = CencMp4Remuxer.remux(
                                                downloader.downloadWithRetry(v.urls), v.cencKey)
                                        File(cacheDir, "clear_" + video.videoId + ".mp4")
                                                .writeBytes(clear)
                                        ok = true
                                        break
                                    } catch (e: Exception) {
                                        AppLog.e("PlayerActivity", "预加载变体失败", e)
                                    }
                                }
                            }
                            if (!ok && video.urls.isNotEmpty()) {
                                try {
                                    val clear = CencMp4Remuxer.remux(
                                            downloader.downloadWithRetry(video.urls), video.cencKey)
                                    File(cacheDir, "clear_" + video.videoId + ".mp4")
                                            .writeBytes(clear)
                                    ok = true
                                } catch (e: Exception) {
                                    AppLog.e("PlayerActivity", "预加载兜底失败", e)
                                }
                            }
                            if (!ok) failedPrefetch.add(vid)
                        }
                        if (gen != playGen) return@launch
                        AppLog.d("PlayerActivity", "预加载完成 pos=$pos vid=$vid")
                    } catch (e: Throwable) {
                        // 失败不打扰用户：切集时走正常路径（playCurrent 会重新取流）。
                        // 记下 vid 本轮不再重试，避免每 500ms 一次形成请求风暴。
                        if (e.message?.contains("cancel", ignoreCase = true) != true) {
                            failedPrefetch.add(vid)
                        }
                        AppLog.d("PlayerActivity", "预加载失败 pos=$pos: " + (e.message ?: ""))
                    }
                }
            } finally {
                prefetchRunning = false
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
            } catch (e: Throwable) {
                // ⚠️ 必须是 Throwable 而不是 Exception：NoClassDefFoundError /
                // NoSuchMethodError 这类 LinkageError 继承自 Error，用 Exception 接不住，
                // 会直接冒到 CoroutineExceptionHandler 让 App 闪退
                // （日志实锤：API 22 设备 java.util.Base64 缺失 → 拉弹幕即闪退）。
                // 弹幕是附属功能，任何失败都不该影响观看。
                AppLog.d("PlayerActivity", "弹幕拉取失败 window=$window: " + (e.message ?: e.javaClass.simpleName))
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

    /* ==================== 切集 ==================== */

    private fun switchEpisode(pos: Int) {
        if (switchingEpisode) {
            // 切集准备中（下载+解密可能数秒）：给个反馈，否则用户以为遥控失灵
            toast("正在切换…")
            return
        }
        if (pos < 0) {
            toast(getString(R.string.first_episode))
            return
        }
        if (pos >= vidList.size) {
            toast(getString(R.string.last_episode))
            return
        }
        switchingEpisode = true
        // 先落盘当前集进度，再切走
        recordHistory(force = true)
        currentPos = pos
        resumePosition = 0
        // 停止上一集；只清理「新预取窗口之外」的明文临时文件。
        // 关键：不能整目录删——新窗口（当前集起 PREFETCH_AHEAD 集）里若已有
        // 预加载文件，删掉会让缓存永远命中不了（预加载失效、切集又变卡）。
        releasePlayer()
        val keep = HashSet<String>()
        val keepEnd = minOf(pos + PREFETCH_AHEAD, vidList.size - 1)
        for (k in pos..keepEnd) vidList.getOrNull(k)?.let { keep.add(it) }
        cleanupClearFiles(keep = keep)
        playCurrent()
        showControls()
        // 注意：switchingEpisode 不在这里复位——要等新集真正 startPlayback
        // 才解除抑制，否则新集准备期间的 STATE_ENDED 会被误判成"本集播完"再次切集。
    }

    /**
     * 一集播完。
     *
     * ⚠️ 用户反馈的「点进去观看短剧会闪退回选集界面」根因就在这里：旧实现无条件
     * 认为 STATE_ENDED 就是"本集看完"，末集直接 finish() → 回到选集页。但切集、
     * seek、解码器初始化失败等场景也会瞬时抛出 STATE_ENDED，于是用户刚进播放页
     * 就被弹回选集页，看起来像"闪退"。
     *
     * 修复：① 切集过程中忽略；② 只在确实播到接近片尾时才认为播完；
     *      ③ 末集不再 finish()，而是停在最后一帧并提示，由用户自己退出。
     */
    private fun onEpisodeComplete() {
        if (switchingEpisode) {
            AppLog.d("PlayerActivity", "忽略切集期间的 STATE_ENDED")
            return
        }
        val p = player
        val dur = p?.duration ?: 0L
        val pos = p?.currentPosition ?: 0L
        // 本集已失败（解码/网络）：不能再当"播完"去切下一集，否则会连环失败跳集
        if (currentEpisodeFailed) {
            AppLog.d("PlayerActivity", "本集起播失败，忽略 STATE_ENDED")
            return
        }
        // ⚠️ duration 未知时 ExoPlayer 返回 C.TIME_UNSET（一个很大的负数）或 0。
        // 此时绝不能当成"播完"——解码器初始化失败/无可用轨道也会抛 STATE_ENDED，
        // 误判会导致进播放页立刻自动跳下一集（观感即"闪退到选集"）。一律忽略。
        if (dur <= 0 || pos < dur - END_TOLERANCE_MS) {
            AppLog.d("PlayerActivity", "忽略虚假 STATE_ENDED pos=$pos dur=$dur")
            return
        }
        // 本集确实播完：清掉本集进度（而非存片尾位置）。
        // 若把片尾位置留作续播点，用户下次进这集会被 seek 到片尾 → 立刻又
        // STATE_ENDED → 又自动跳下一集，而下一集的片尾位置同样被留下，形成
        // 「周而复始自动跳集」（用户反馈的恶性 bug）。markEpisodeCompleted 会
        // 清空该集进度并把 lastEp 推进到本集。
        markEpisodeCompleted()
        if (currentPos + 1 < vidList.size) {
            switchEpisode(currentPos + 1)
        } else {
            // 末集播完：不再自动退出（那是"闪退回选集"的观感），停在原地给提示
            toast(getString(R.string.ep_played_out))
            showControls()
        }
    }

    /** 标记当前集已看完：清空该集进度，避免下次进入又被 seek 到片尾连跳。 */
    private fun markEpisodeCompleted() {
        if (seriesId.isEmpty()) return
        try {
            App.watchHistory.markEpisodeCompleted(seriesId, title, cover, currentEpisode())
        } catch (e: Exception) {
            AppLog.e("PlayerActivity", "标记看完失败", e)
        }
    }

    /** 当前集号：优先取 epList[位置]，列表缺失时退化为「位置 + 1」 */
    private fun currentEpisode(): Int = epList.getOrNull(currentPos) ?: (currentPos + 1)

    /**
     * 写入观看历史（含集内进度）。
     * @param force true 表示关键节点（切集/暂停/退出）必须落盘；false 走节流（10s 一次）
     *
     * WatchHistory 现按「集号→进度」映射存储，写本集不会影响其他集的进度。
     * 仍保留防抹除：位置读不到（prepare 未完成，duration=0 时 currentPosition
     * 恒为 0）时跳过该集的进度写入，只更新时间戳语义由 record() 内部保证。
     */
    private fun recordHistory(force: Boolean = false) {
        if (seriesId.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryWrite < HISTORY_WRITE_INTERVAL_MS) return
        val p = player
        // 读位置（prepare 未完成时 duration<=0，读作 0 表示"本次拿不到有效位置"）
        var posMs = try {
            if (p != null && p.duration > 0) p.currentPosition else 0L
        } catch (_: Exception) {
            0L
        }
        // ⚠️ 接近片尾的位置一律不写入历史。否则该位置会成为下次的续播点，
        // 用户再进这集就被 seek 到片尾 → 立刻 STATE_ENDED → 自动跳下一集，
        // 而下一集同样留下片尾位置 → 连环跳集（用户反馈的恶性 bug）。
        // 这里"直接 return 不写"而非写 0：record() 对 <=0 是"保留旧值"语义，
        // 写 0 清不掉，反而可能把更早的有效续播点保留下来造成误解。
        if (posMs > 0) {
            val dur = try { p?.duration ?: 0L } catch (_: Exception) { 0L }
            if (dur > 0 && posMs >= dur - END_TOLERANCE_MS) {
                lastHistoryWrite = now
                return
            }
        }
        lastHistoryWrite = now
        try {
            App.watchHistory.record(seriesId, title, cover, currentEpisode(), posMs)
        } catch (e: Exception) {
            AppLog.e("PlayerActivity", "写历史失败", e)
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
            try { it.clearMediaItems() } catch (_: Exception) {}
        }
    }

    /**
     * 删除 cacheDir 下 clear_ 前缀的明文临时文件。
     * @param keep 需保留的 vid 集合（当前集与预加载的下一集）；为 null 时全清。
     */
    private fun cleanupClearFiles(keep: Set<String?>? = null) {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (!f.name.startsWith("clear_")) return@forEach
                if (keep != null) {
                    val name = f.name.removePrefix("clear_").removeSuffix(".mp4")
                    if (keep.contains(name)) return@forEach
                }
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun showError(message: String) {
        errorText.text = getString(R.string.play_error, message)
        errorText.visibility = View.VISIBLE
        showControls()
    }

    /* ==================== 覆盖层显示 ==================== */

    /** 短暂显示顶部信息条 + 底部进度条（都不是焦点控件，不会抢焦点）。 */
    private fun showControls() {
        topBar.visibility = View.VISIBLE
        controlBar.visibility = View.VISIBLE
        updateProgressText()
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private val hideControlsRunnable = Runnable {
        topBar.visibility = View.GONE
        controlBar.visibility = View.GONE
    }

    /** 居中短暂提示（快进/快退/切集/开关状态）。 */
    private fun toast(msg: String) {
        toastHint.text = msg
        toastHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, 1200)
    }

    private val hideToastRunnable = Runnable { toastHint.visibility = View.GONE }

    private fun updateProgressText() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        positionText.text = formatMs(pos)
        durationText.text = if (dur > 0) formatMs(dur) else "--:--"
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** dp → px（选集弹窗动态建格子用）。 */
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    /* ==================== 菜单 ==================== */

    private fun toggleMenu() {
        if (menuPanel.visibility == View.VISIBLE) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        menuPanel.visibility = View.VISIBLE
        updateAspectLabel()
        updateSpeedLabel()
        updateDanmakuLabel()
        menuFill.post { menuFill.requestFocus() }
    }

    private fun hideMenu() {
        menuPanel.visibility = View.GONE
        // 菜单隐藏后焦点会留在已 GONE 的按钮上，TV 上会导致"方向键全无反应"。
        // 把焦点交给隐形锚点：锚点不消费任何按键，全部按键仍走 Activity.onKeyDown。
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
    }

    /* ==================== 进度刷新（500ms） ==================== */

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !tracking) {
                val dur = p.duration
                val pos = p.currentPosition
                if (dur > 0) {
                    seekBar.max = 1000
                    seekBar.progress = (pos * 1000 / dur).toInt()
                    updateProgressText()
                    // 播放到 80% 时预加载下一集。放在这里而非弹幕函数内，
                    // 避免「关闭弹幕导致预加载一并失效」。
                    if (pos >= dur * 4 / 5) prefetchNext()
                }
                updateDanmaku(p, pos, dur)
                // 周期性落盘进度（节流 10s），保证异常退出也不丢进度
                recordHistory(force = false)
            }
            handler.postDelayed(this, 500)
        }
    }

    /* ==================== 按键 ==================== */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 选集弹窗展开时：返回/菜单关闭，其余方向键+确定交给系统做焦点导航
        if (epPickerRoot.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> { closeEpisodePicker(); return true }
                KeyEvent.KEYCODE_BACK -> { closeEpisodePicker(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
        }
        // 菜单展开时：只接管菜单键/返回键，方向键留给系统做焦点导航
        if (menuPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> { hideMenu(); return true }
                KeyEvent.KEYCODE_BACK -> { hideMenu(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 双击返回退出：与其他电视应用一致。首次按提示，2 秒内再按一次才退出。
                onBackPressed2x()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showMenu()
                return true
            }
            // 遥控器确定键：直接暂停/继续播放（用户诉求）
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            // 左右键：快退 / 快进。长按会持续收到 repeatCount>0 的重复事件，
            // 步长随按住时长线性放大（15s → 30s → 45s …），实现"长按叠加时长"。
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                quickSeek(-1, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                quickSeek(1, event.repeatCount)
                return true
            }
            // 上下键：上一集 / 下一集
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchEpisode(currentPos - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchEpisode(currentPos + 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                recordHistory(force = true)
                showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
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
     * 快进/快退。
     * @param dir -1 快退，+1 快进
     * @param repeatCount 系统长按重复计数；0 表示首次按下，步长 15s；
     *                    每次重复步长递增 15s，让长按越久跳得越多。
     */
    private fun quickSeek(dir: Int, repeatCount: Int) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        // 用户已手动操作进度：作废待执行的续播 seek，避免 STATE_READY 回调
        // 把用户刚拖到的位置又覆盖回续播点（READY 晚于按键到达的竞态）。
        pendingResumeMs = 0L
        val step = QUICK_SEEK_MS * (1 + repeatCount.coerceAtMost(MAX_SEEK_REPEAT).toLong())
        val target = (p.currentPosition + dir * step).coerceIn(0L, dur)
        p.seekTo(target)
        // 往回跳时清掉已拉取窗口标记，允许重新拉取（往前跳旧窗口数据仍有效，避免重复请求）。
        // DanmakuView.setPositionMs 会自行识别位置跳变并重基准在途弹幕，无需在此手动清理。
        if (dir < 0) {
            danmakuFetchedWindows.clear()
            lastDanmakuWindow = -1L
        }
        danmakuView.setPositionMs(target)
        showControls()
        toast(if (dir > 0) getString(R.string.seek_forward, (step / 1000).toString() + "s")
        else getString(R.string.seek_backward, (step / 1000).toString() + "s"))
    }

    /* ==================== 退出 ==================== */

    /**
     * 双击返回退出：第一次按给出提示，[EXIT_INTERVAL_MS] 内再按一次才真正退出。
     * 与主流电视应用（爱奇艺/腾讯视频 TV 版等）一致，避免「按一下返回就退出」的误操作。
     */
    private fun onBackPressed2x() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressed < EXIT_INTERVAL_MS) {
            finish()
        } else {
            lastBackPressed = now
            toast(getString(R.string.press_again_to_exit))
        }
    }

    /**
     * 退出确认对话框（仅菜单里的「退出播放」入口使用）。
     *
     * 返回键已改为双击退出，不再走这里。原设 cancelable(false) 导致部分 TV ROM 上
     * 对话框按钮无初始焦点时返回键被吞，用户被困在播放页；保持 cancelable(true) 并在
     * onShow 显式聚焦「继续播放」。
     */
    /* ==================== 选集弹窗 ==================== */

    /** 每页集数：5 列 × 4 行 = 20（与参考图一致）。 */
    private val EP_PAGE_SIZE = 20
    private val EP_COLUMNS = 5

    /** 打开选集弹窗：定位到当前集所在页，暂停播放并抢焦点。 */
    private fun openEpisodePicker() {
        if (vidList.isEmpty()) return
        hideMenu()
        // 弹窗是独立焦点域：打开时暂停，避免用户在选集时声音继续
        tracking = false
        val p = player
        p?.pause()
        // 当前集所在页起始下标
        epGroupStart = (currentPos / EP_PAGE_SIZE) * EP_PAGE_SIZE
        buildEpisodeGrid()
        epPickerRoot.visibility = View.VISIBLE
        // 焦点落到当前集格子（若在本页），否则落到网格第一个
        epGrid.post {
            val cur = epCells.getOrNull(currentPos - epGroupStart)
            (cur ?: epCells.firstOrNull())?.requestFocus()
        }
    }

    private fun closeEpisodePicker() {
        epPickerRoot.visibility = View.GONE
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
        showControls()
    }

    /** 生成/刷新网格：复用 epCells，填充当前页的集数按钮。 */
    private fun buildEpisodeGrid() {
        val total = vidList.size
        epPickerTitle.text = getString(R.string.ep_picker_title) + " / " +
                getString(R.string.ep_picker_subtitle, currentEpisode())
        val pageEnd = minOf(epGroupStart + EP_PAGE_SIZE, total)
        epPickerRange.text = getString(
                R.string.ep_picker_range,
                epGroupStart + 1, pageEnd, total)

        // 确保格子数量够一页
        while (epCells.size < EP_PAGE_SIZE) {
            val tv = TextView(this)
            tv.setTextColor(resources.getColor(R.color.text_primary))
            tv.textSize = 15f
            tv.gravity = android.view.Gravity.CENTER
            tv.background = resources.getDrawable(R.drawable.ep_cell_bg)
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.isClickable = true
            val lp = GridLayout.LayoutParams()
            lp.width = dp(90)
            lp.height = dp(48)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            // 靠 GridLayout columnCount=5 自动流式换行，格子固定宽，避免 weight 布局踩坑
            tv.layoutParams = lp
            epGrid.addView(tv)
            epCells.add(tv)
        }
        epGrid.removeAllViews()

        for (i in 0 until EP_PAGE_SIZE) {
            val tv = epCells[i]
            val idx = epGroupStart + i
            if (idx < total) {
                tv.text = (epList.getOrNull(idx) ?: (idx + 1)).toString()
                tv.visibility = View.VISIBLE
                // 当前集高亮（selected 态）
                tv.isSelected = (idx == currentPos)
                tv.setOnClickListener {
                    closeEpisodePicker()
                    switchEpisode(idx)
                }
            } else {
                tv.visibility = View.INVISIBLE
                tv.setOnClickListener(null)
            }
            epGrid.addView(tv)
        }
        // 上一组/下一组可用性
        epBtnPrev.isEnabled = epGroupStart > 0
        epBtnPrev.alpha = if (epGroupStart > 0) 1f else 0.4f
        epBtnNext.isEnabled = pageEnd < total
        epBtnNext.alpha = if (pageEnd < total) 1f else 0.4f
    }

    /** 切换上/下一页（组）。 */
    private fun switchEpGroup(dir: Int) {
        val total = vidList.size
        var start = epGroupStart + dir * EP_PAGE_SIZE
        if (start < 0) start = 0
        if (start >= total) start = (total - 1) / EP_PAGE_SIZE * EP_PAGE_SIZE
        if (start == epGroupStart) return
        epGroupStart = start
        buildEpisodeGrid()
        epCells.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
    }

    /** 跳转集数：输入框弹窗，输入目标集号后按下标切集。 */
    private fun showJumpDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.ep_picker_jump_hint)
        val pad = dp(24)
        val container = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(pad, dp(8), pad, 0)
        input.layoutParams = lp
        container.addView(input)
        val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.ep_picker_jump)
                .setView(container)
                .setPositiveButton("跳转") { _, _ ->
                    val n = input.text.toString().toIntOrNull()
                    if (n != null) jumpToEpisode(n)
                }
                .setNegativeButton("取消", null)
                .create()
        dialog.setOnShowListener {
            try { input.requestFocus() } catch (_: Exception) {}
        }
        dialog.show()
    }

    /** 按集号（epList 里的显示值）跳转；找不到则提示。 */
    private fun jumpToEpisode(epNumber: Int) {
        val idx = epList.indexOf(epNumber).let {
            if (it >= 0) it else if (epNumber in 1..vidList.size) epNumber - 1 else -1
        }
        if (idx < 0 || idx >= vidList.size) {
            toast(getString(R.string.ep_picker_jump_invalid))
            return
        }
        closeEpisodePicker()
        switchEpisode(idx)
    }

    private fun showExitDialog() {
        tracking = false
        val p = player
        val wasPlaying = p?.isPlaying == true
        p?.pause()
        recordHistory(force = true)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("继续播放还是退出？")
            .setPositiveButton("继续播放") { _, _ ->
                if (wasPlaying) player?.play()
                showControls()
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
        dialog.show()
    }

    /* ==================== 生命周期 ==================== */

    override fun onStop() {
        super.onStop()
        // 用户反馈：按主页返回后后台还在出声音。Activity 不可见时必须暂停。
        // shouldBePlaying 记录「用户意图」，回前台据此决定是否自动续播——
        // 避免把用户手动暂停的播放强行续起。
        shouldBePlaying = player?.isPlaying == true
        resumePosition = try {
            val p = player
            if (p != null && p.duration > 0) p.currentPosition.toInt() else 0
        } catch (_: Exception) {
            0
        }
        recordHistory(force = true)
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        val p = player ?: return
        // 从后台/按主页返回后回前台：回到原位并仅在「用户意图是播放」时续播
        if (resumePosition > 0) {
            try {
                if (p.duration > 0) p.seekTo(resumePosition.toLong())
            } catch (_: Exception) {
            }
            resumePosition = 0
        }
        if (shouldBePlaying && p.playbackState != Player.STATE_IDLE) {
            p.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退出/切走前把进度落盘（onDestroy 不保证一定执行）
        recordHistory(force = true)
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        recordHistory(force = true)
        try { player?.release() } catch (_: Exception) {}
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

        /** 快进退基础步长：15 秒 */
        private const val QUICK_SEEK_MS = 15_000L
        /** 长按叠加的最大重复级数（避免一次跨太多） */
        private const val MAX_SEEK_REPEAT = 7

        /** 判为"真的播完"的容差：位置需在片尾 1.5s 内 */
        private const val END_TOLERANCE_MS = 1500L
        /** 续播的最小进度：小于 5s 视为从头看 */
        private const val RESUME_MIN_MS = 5_000L
        /**
         * 续播的片尾余量：上次位置距片尾不足该值（或时长 10%，取小）时视为已看完，
         * 从头播。若只留几秒余量，用户续播进去还没看清就 STATE_ENDED 自动跳集，
         * 观感仍是"点进去就跳下一集"（用户反馈的恶性 bug 场景）。
         */
        private const val RESUME_TAIL_MARGIN_MS = 15_000L
        /** 播放中写观看历史的节流间隔 */
        private const val HISTORY_WRITE_INTERVAL_MS = 10_000L

        /** 双击返回退出的时间窗口：2 秒内连按两次返回键直接退出 */
        private const val EXIT_INTERVAL_MS = 2_000L

        /**
         * 切集抑制兜底时限：正常起播（含下载+解密）远快于此。
         * 到点仍未收到 STATE_READY/onPlayerError 就强制解除，保证上下键不锁死。
         */
        private const val SWITCH_GUARD_MS = 45_000L

        /** tkhd 解析时读取的文件头长度（moov 通常在前 256KB 内）。 */
        private const val MOOV_PROBE_BYTES = 256 * 1024

        /**
         * 预加载窗口：提前把当前集之后 N 集下载解密进缓存，实现丝滑切集
         * （含选集弹窗任意跳转）。单集约 5~11MB，N=5 时峰值占用约 50MB，
         * 低内存盒子可接受；再大收益递减且挤占存储。
         */
        private const val PREFETCH_AHEAD = 5

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
