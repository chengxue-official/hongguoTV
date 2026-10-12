package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.data.WatchHistory

/** 追剧历史页 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        adapter = HistoryAdapter(
            onItemClick = { entry -> openDetail(entry) },
            onLongClick = { entry -> confirmRemove(entry) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = App.watchHistory.list()
        adapter.submit(list)
        val statusText = findViewById<TextView>(R.id.status_text)
        if (list.isEmpty()) {
            statusText.text = getString(R.string.empty_history)
            statusText.visibility = View.VISIBLE
        } else {
            statusText.visibility = View.GONE
        }
    }

    private fun openDetail(entry: WatchHistory.Entry) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, entry.seriesId)
        intent.putExtra(DetailActivity.EXTRA_TITLE, entry.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, entry.cover)
        startActivity(intent)
    }

    private fun confirmRemove(entry: WatchHistory.Entry) {
        AlertDialog.Builder(this)
            .setTitle("移除追剧")
            .setMessage("确定从追剧列表移除「${entry.title}」吗？")
            .setPositiveButton("移除") { _, _ ->
                App.watchHistory.remove(entry.seriesId)
                refresh()
                Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
