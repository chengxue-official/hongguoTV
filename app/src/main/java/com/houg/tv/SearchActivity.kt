package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import com.houg.tv.repo.DramaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：支持三种搜索方式 + 内置软键盘兜底。
 * 1. 全名/关键词搜索：服务端 /search/<词>（红果自有搜索接口）。
 * 2. 拼音首字母搜索：本地片名索引（剧名→拼音首字母），输入纯字母时匹配。
 * 3. 内置软键盘：部分电视无法唤起系统键盘时，用 App 内键盘输入。
 */
class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var input: EditText
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var keyboardContainer: FrameLayout
    private var softKeyboard: SoftKeyboard? = null
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
        keyboardContainer = findViewById(R.id.soft_keyboard_container)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = GridLayoutManager(this, adaptiveGridColumns(this, 150, 2, 10))
        recycler.adapter = adapter

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_do_search).setOnClickListener { doSearch() }
        findViewById<View>(R.id.btn_keyboard).setOnClickListener { toggleKeyboard() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        // 输入框聚焦时展示内置软键盘（TV 上系统键盘可能唤不出）
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showKeyboard() else hideKeyboard()
        }

        // 初始化内置软键盘
        initSoftKeyboard()

        // 先读本地索引；若缺失则后台重建
        val cached = DramaIndex.load(this)
        indexReady = cached.isNotEmpty()
        if (!indexReady) {
            rebuildIndexInBackground()
        }

        input.requestFocus()
        // TV 上主动唤起系统软键盘（有则用，无则靠内置键盘兜底）
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun initSoftKeyboard() {
        val kb = SoftKeyboard(this)
        kb.setOnKey { c -> appendChar(c) }
        kb.setOnCmd { cmd ->
            when (cmd) {
                SoftKeyboard.Cmd.BACKSPACE -> backspace()
                SoftKeyboard.Cmd.CLEAR -> { input.setText(""); showKeyboard() }
                SoftKeyboard.Cmd.SPACE -> appendChar(' ')
                SoftKeyboard.Cmd.SEARCH -> doSearch()
            }
        }
        softKeyboard = kb
        keyboardContainer.addView(kb)
    }

    private fun appendChar(c: Char) {
        val cur = input.text.toString()
        val sel = input.selectionStart.coerceIn(0, cur.length)
        val next = cur.substring(0, sel) + c + cur.substring(sel)
        input.setText(next)
        input.setSelection(sel + 1)
    }

    private fun backspace() {
        val cur = input.text.toString()
        if (cur.isEmpty()) return
        val sel = input.selectionStart.coerceIn(1, cur.length)
        val next = cur.substring(0, sel - 1) + cur.substring(sel)
        input.setText(next)
        input.setSelection(sel - 1)
    }

    private fun toggleKeyboard() {
        if (keyboardContainer.visibility == View.VISIBLE) hideKeyboard() else showKeyboard()
    }

    private fun showKeyboard() {
        keyboardContainer.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
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
        // 收起系统软键盘，避免遮挡结果列表
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        hideKeyboard()
        findViewById<View>(R.id.btn_do_search).requestFocus()

        searchGen++
        val gen = searchGen
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        adapter.submit(emptyList())

        scope.launch {
            try {
                // 本地首字母/标题匹配（索引已就绪时优先本地，命中则直接出结果）
                val local = if (indexReady) {
                    withContext(Dispatchers.IO) { DramaIndex.search(keyword) }
                } else emptyList()

                if (local.isNotEmpty()) {
                    if (gen == searchGen) {
                        adapter.submit(local.map { e ->
                            NetClient.Drama.minimal(e.id, e.title, e.cover)
                        })
                    }
                } else {
                    // 本地无命中 → 服务端关键词搜索兜底
                    val result = withContext(Dispatchers.IO) {
                        App.netClient.search(keyword)
                    }
                    if (gen != searchGen) return@launch
                    adapter.submit(result)
                    if (result.isEmpty()) {
                        statusText.text = getString(R.string.empty)
                        statusText.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                if (gen != searchGen) return@launch
                statusText.text = getString(R.string.load_failed)
                statusText.visibility = View.VISIBLE
            } finally {
                if (gen == searchGen) progress.visibility = View.GONE
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
