package com.houg.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import com.houg.tv.update.UpdateChecker
import com.houg.tv.update.UpdateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页：真人剧 / 漫剧 / AI剧 三个分类 + 搜索 / 追剧入口 */
class MainActivity : AppCompatActivity() {

    private data class Genre(val key: String, val scene: String, val label: String)

    private val genres = listOf(
        Genre("short_play", "default", "真人剧"),
        Genre("comic_series", "comic_series", "漫剧"),
        Genre("ai_series", "ai_series", "AI剧")
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView

    private var currentGenre = 0
    private var nextOffset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    /** 请求代次：快速切分类时丢弃过期响应，避免数据错位 */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        setContentView(R.layout.activity_main)

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        recycler = findViewById(R.id.recycler)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = GridLayoutManager(this, adaptiveGridColumns(this, 150, 2, 10))
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 6 && hasMore && !loading) {
                    loadPage(false)
                }
            }
        })

        setupTabs()
        setupNavButtons()

        selectGenre(0)
        silentCheckUpdate()
    }

    /**
     * 启动时静默检查更新：只在「确实有新版本」时才弹窗，检查失败/已最新都静默，
     * 避免每次开机打扰用户。用户也可随时点「检查更新」手动触发并看到结果。
     */
    private fun silentCheckUpdate() {
        scope.launch {
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (e: Exception) {
                -1
            }
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
            if (result.hasUpdate) UpdateDialog.start(this@MainActivity, scope)
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            findViewById<TextView>(R.id.tab_real),
            findViewById<TextView>(R.id.tab_comic),
            findViewById<TextView>(R.id.tab_ai)
        )
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectGenre(index) }
        }
    }

    private fun setupNavButtons() {
        findViewById<TextView>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_HISTORY))
        }
        findViewById<TextView>(R.id.btn_favorite).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_FAVORITE))
        }
        findViewById<TextView>(R.id.btn_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_update).setOnClickListener {
            UpdateDialog.start(this, scope)
        }
    }

    private fun selectGenre(index: Int) {
        currentGenre = index
        generation++
        val tabs = listOf(
            findViewById<TextView>(R.id.tab_real),
            findViewById<TextView>(R.id.tab_comic),
            findViewById<TextView>(R.id.tab_ai)
        )
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        // 允许新分类立即发起请求，旧请求由 generation 校验丢弃
        loading = false
        loadPage(true)
    }

    private fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        val gen = generation
        val genre = genres[currentGenre]
        val offset = if (reset) 0 else nextOffset
        val session = if (reset) "" else sessionId

        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    App.netClient.catalog(genre.key, genre.scene, offset, session)
                }
                if (gen != generation) return@launch
                if (reset) {
                    adapter.submit(result.dramas)
                } else {
                    adapter.append(result.dramas)
                }
                nextOffset = result.nextOffset
                sessionId = result.sessionId
                hasMore = result.hasMore
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                if (gen == generation) {
                    progress.visibility = View.GONE
                    loading = false
                }
            }
        }
    }

    private fun openDetail(drama: NetClient.Drama) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
        intent.putExtra(DetailActivity.EXTRA_TITLE, drama.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        configureImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureImmersive()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 横屏全屏（兼容 API 14） */
    private fun configureImmersive() {
        requestedOrientation = 6 // SENSOR_LANDSCAPE
        val decor = window.decorView
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 19) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        decor.systemUiVisibility = flags
    }

    /** 首页返回键：弹确认退出，避免误触直接退出软件 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitConfirm()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirm() {
        AlertDialog.Builder(this)
            .setTitle("退出")
            .setMessage("确定退出红果短剧吗？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }
}
