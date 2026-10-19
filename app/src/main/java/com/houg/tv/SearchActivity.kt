package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.LanInputServer
import com.houg.tv.net.NetClient
import com.houg.tv.repo.DramaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：支持三种搜索方式 + 局域网跨屏输入。
 * 1. 全名/关键词搜索：服务端 /search/<词>（红果自有搜索接口）。
 * 2. 拼音首字母搜索：本地片名索引（剧名→拼音首字母），输入纯字母时匹配。
 * 3. 局域网跨屏输入：电视起本地 HTTP 服务，手机同 Wi-Fi 打开提示网址，
 *    在手机网页上打字回传搜索 —— 取代内置软键盘（遥控器打字体验差，
 *    系统输入法在老盒子上又常唤不出来）。
 */
class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var input: EditText
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var historyPanel: View
    private lateinit var historyChips: RecyclerView
    private lateinit var historyAdapter: SearchChipAdapter
    private lateinit var lanPanel: View
    private lateinit var lanHint: TextView
    /** 局域网跨屏输入服务（仅本页存活期间运行） */
    private var lanServer: LanInputServer? = null
    /** 搜索代次：快速连续搜索时丢弃过期响应 */
    private var searchGen = 0
    /** 本地片名索引是否已就绪 */
    private var indexReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        historyPanel = findViewById(R.id.history_panel)
        historyChips = findViewById(R.id.history_chips)
        lanPanel = findViewById(R.id.lan_panel)
        lanHint = findViewById(R.id.lan_hint)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = GridLayoutManager(this, adaptiveGridColumns(this, 150, 2, 10))
        recycler.adapter = adapter

        // 搜索记录：用 RecyclerView 自动换行展示，遥控器可直接导航到每条
        historyAdapter = SearchChipAdapter(
            onClick = { kw ->
                input.setText(kw)
                input.setSelection(kw.length)
                doSearch()
            },
            onLongClick = { kw ->
                App.searchHistory.remove(kw)
                renderSearchHistory()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        )
        historyChips.layoutManager =
            GridLayoutManager(this, adaptiveGridColumns(this, 130, 2, 8))
        historyChips.adapter = historyAdapter

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_do_search).setOnClickListener { doSearch() }
        findViewById<View>(R.id.btn_lan_input).setOnClickListener { toggleLanInput() }
        findViewById<View>(R.id.btn_clear_history).setOnClickListener { confirmClearHistory() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 先读本地索引；若缺失则后台重建
        val cached = DramaIndex.load(this)
        indexReady = cached.isNotEmpty()
        if (!indexReady) {
            rebuildIndexInBackground()
        }

        renderSearchHistory()

        input.requestFocus()
    }

    /* ==================== 搜索记录 ==================== */

    /** 渲染搜索记录胶囊（最多展示 CHIP_MAX 条，避免铺满屏幕）。 */
    private fun renderSearchHistory() {
        val list = App.searchHistory.list()
        if (list.isEmpty()) {
            historyPanel.visibility = View.GONE
            historyAdapter.submit(emptyList())
            return
        }
        historyPanel.visibility = View.VISIBLE
        historyAdapter.submit(list.take(CHIP_MAX))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear))
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                App.searchHistory.clear()
                renderSearchHistory()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ==================== 局域网跨屏输入 ==================== */

    /**
     * 开关局域网输入：开启后在 TV 上起本地 HTTP 服务，页面提示手机访问地址。
     * 手机与 TV 需在同一 Wi-Fi；手机打开网址 → 输入剧名 → 回传后立即搜索。
     */
    private fun toggleLanInput() {
        if (lanServer != null) {
            stopLanInput()
            return
        }
        val server = LanInputServer { keyword ->
            input.setText(keyword)
            input.setSelection(keyword.length)
            doSearch()
        }
        val port = server.start()
        val address = server.localAddress()
        if (port == 0 || address == null) {
            server.stop()
            lanHint.text = getString(R.string.lan_input_unavailable)
            lanPanel.visibility = View.VISIBLE
            Toast.makeText(this, R.string.lan_input_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        lanServer = server
        val url = "http://$address:$port"
        lanHint.text = getString(R.string.lan_input_hint, url)
        lanPanel.visibility = View.VISIBLE
        Toast.makeText(this, getString(R.string.lan_input_hint, url), Toast.LENGTH_LONG).show()
    }

    private fun stopLanInput() {
        lanServer?.stop()
        lanServer = null
        lanPanel.visibility = View.GONE
    }

    /** 后台重建片名索引（首次进入且无本地缓存时）。 */
    private fun rebuildIndexInBackground() {
        progress.visibility = View.VISIBLE
        statusText.text = getString(R.string.index_building)
        statusText.visibility = View.VISIBLE
        scope.launch {
            try {
                DramaIndex.rebuild(this@SearchActivity, App.netClient)
                indexReady = true
            } catch (e: Exception) {
                indexReady = false
            } finally {
                statusText.visibility = View.GONE
                progress.visibility = View.GONE
            }
        }
    }

    private fun doSearch() {
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty()) return
        // 记入搜索历史（去重上浮），并刷新胶囊区
        App.searchHistory.add(keyword)
        renderSearchHistory()
        input.clearFocus()
        findViewById<View>(R.id.btn_do_search).requestFocus()

        searchGen++
        val gen = searchGen
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        adapter.submit(emptyList())

        scope.launch {
            try {
                // 本地首字母/标题匹配（索引已就绪时先出结果，保证秒开）
                val local = if (indexReady) {
                    withContext(Dispatchers.IO) { DramaIndex.search(keyword) }
                } else emptyList()
                val localDramas = local.map { e ->
                    NetClient.Drama.minimal(e.id, e.title, e.cover)
                }
                if (gen == searchGen && localDramas.isNotEmpty()) {
                    adapter.submit(localDramas)
                }

                // 服务端搜索兜底/补全：本地索引来自分类翻页（每分类有页数上限），
                // 排在截断点之后的剧会缺失（例：多季剧缺个别季，如用户反馈的
                // 「唢呐修仙」缺 6/8/9 季）。因此本地命中后仍查一次服务端，
                // 用更全的数据合并修正（建议接口单次即可召回该系列全部季）。
                val remote = withContext(Dispatchers.IO) {
                    App.netClient.search(keyword)
                }
                if (gen != searchGen) return@launch
                val merged = mergeResults(localDramas, remote)
                adapter.submit(merged)
                if (merged.isEmpty()) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != searchGen) return@launch
                // 本地结果已展示时不覆盖为错误提示，仅无结果时提示
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                if (gen == searchGen) progress.visibility = View.GONE
            }
        }
    }

    /**
     * 合并本地与服务端结果：本地序优先，服务端新增的排在后面。
     * 去重按 id 和归一化标题双键：
     * - 同 id：用服务端更全的数据替换（本地 minimal 只有 id/title/cover）；
     * - 同标题不同 id：保留服务端那条（本地索引的 id 可能是旧版精度 bug
     *   写入磁盘的坏 ID，点进去必然 404，用户看到的「重复的第十季，
     *   一个连不上一个能看」即此因）。
     */
    private fun mergeResults(
        local: List<NetClient.Drama>,
        remote: List<NetClient.Drama>,
    ): List<NetClient.Drama> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local
        val remoteById = HashMap<String, NetClient.Drama>()
        val remoteByTitle = HashMap<String, NetClient.Drama>()
        for (d in remote) {
            remoteById[d.id] = d
            remoteByTitle[normKey(d.title)] = d
        }
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        val result = ArrayList<NetClient.Drama>(local.size + remote.size)
        for (d in local) {
            val tk = normKey(d.title)
            val better = remoteByTitle[tk] ?: remoteById[d.id] ?: d
            if (!seenIds.add(better.id)) continue
            if (!seenTitles.add(tk)) continue
            result.add(better)
        }
        for (d in remote) {
            if (!seenIds.add(d.id)) continue
            if (!seenTitles.add(normKey(d.title))) continue
            result.add(d)
        }
        return result
    }

    /** 标题归一化去重键：去空白与常见标点、全角转半角、转小写。 */
    private fun normKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val v = c.code
            var ch = c
            if (v in 0xFF01..0xFF5E) ch = (v - 0xFEE0).toChar()
            if (ch == '　') continue
            if (ch.isWhitespace()) continue
            if (ch in "《》·_-：:，,！!？?\"'“”‘’") continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun openDetail(drama: NetClient.Drama) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
        intent.putExtra(DetailActivity.EXTRA_TITLE, drama.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        startActivity(intent)
    }

    override fun onDestroy() {
        stopLanInput()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 搜索记录最多展示的胶囊数（超出需点「清空」管理，避免铺满屏幕） */
        const val CHIP_MAX = 8
    }
}
