package com.houg.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 榜单页：红果官方排行榜（热播 / 真人剧 / 漫剧 / AI剧）。
 * 数据来自网页 {@code /rank/<board>}，与搜索同站免签名；支持上下翻页。
 */
class RankActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: RankAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var updatedText: TextView
    private lateinit var tabs: List<TextView>

    private var boardIndex = 0
    private var page = 1
    private var totalPages = 1
    private var loading = false
    /** 请求代次：快速切榜单时丢弃过期响应 */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        setContentView(R.layout.activity_rank)

        progress = findViewById(R.id.rank_progress)
        statusText = findViewById(R.id.rank_status)
        updatedText = findViewById(R.id.rank_updated)
        recycler = findViewById(R.id.rank_list)

        adapter = RankAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    if (page < totalPages && !loading) loadPage(page + 1, false)
                }
            }
        })

        tabs = listOf(
            findViewById(R.id.board_hot),
            findViewById(R.id.board_real),
            findViewById(R.id.board_comic),
            findViewById(R.id.board_ai)
        )
        tabs.forEachIndexed { index, tab ->
            tab.text = NetClient.RANK_BOARDS[index].label
            tab.setOnClickListener { selectBoard(index) }
        }
        findViewById<TextView>(R.id.rank_back).setOnClickListener { finish() }

        selectBoard(0)
    }

    private fun selectBoard(index: Int) {
        if (boardIndex == index && adapter.itemCount > 0) return
        boardIndex = index
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        loadPage(1, true)
    }

    private fun loadPage(target: Int, reset: Boolean) {
        if (loading) return
        loading = true
        generation++
        val gen = generation
        val board = NetClient.RANK_BOARDS[boardIndex]

        progress.visibility = View.VISIBLE
        if (reset) {
            statusText.visibility = View.GONE
            adapter.submit(emptyList())
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    App.netClient.ranking(board, target)
                }
                if (gen != generation) return@launch
                page = result.page
                totalPages = result.totalPages
                if (reset) adapter.submit(result.items) else adapter.append(result.items)
                updatedText.text = result.updatedText
                updatedText.visibility =
                    if (result.updatedText.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.rank_empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.rank_load_failed)
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
        startActivity(Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
            .putExtra(DetailActivity.EXTRA_TITLE, drama.title)
            .putExtra(DetailActivity.EXTRA_COVER, drama.cover))
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

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
}
