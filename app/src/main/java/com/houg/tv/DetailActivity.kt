package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.net.NetClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页：封面 + 简介 + 选集 */
class DetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var seriesId: String
    private lateinit var seriesTitle: String
    private lateinit var seriesCover: String

    private var episodes: List<NetClient.Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        seriesTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        seriesCover = intent.getStringExtra(EXTRA_COVER) ?: ""

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val titleView = findViewById<TextView>(R.id.detail_title)
        titleView.text = seriesTitle

        val cover = findViewById<ImageView>(R.id.detail_cover)
        if (seriesCover.isNotEmpty()) {
            Glide.with(this).load(seriesCover)
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .into(cover)
        }

        loadDetail()
    }

    private fun loadDetail() {
        val progress = findViewById<View>(R.id.progress)
        val statusText = findViewById<TextView>(R.id.status_text)
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE

        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    App.netClient.detail(seriesId)
                }
                episodes = detail.episodes
                val titleView = findViewById<TextView>(R.id.detail_title)
                titleView.text = detail.title
                val meta = findViewById<TextView>(R.id.detail_meta)
                meta.text = getString(R.string.episode_count, episodes.size.toString())
                val intro = findViewById<TextView>(R.id.detail_intro)
                intro.text = detail.intro
                if (detail.cover.isNotEmpty()) {
                    Glide.with(this@DetailActivity).load(detail.cover)
                        .placeholder(android.R.color.darker_gray)
                        .error(android.R.color.darker_gray)
                        .into(findViewById(R.id.detail_cover))
                }

                val lastEp = App.watchHistory.lastEpisode(seriesId)
                val episodeAdapter = EpisodeAdapter(episodes, lastEp) { ep -> playEpisode(ep) }
                val recycler = findViewById<RecyclerView>(R.id.episode_recycler)
                recycler.layoutManager = GridLayoutManager(
                    this@DetailActivity, adaptiveGridColumns(this@DetailActivity, 100, 4, 12))
                recycler.adapter = episodeAdapter
                // 焦点移到选集列表，方便遥控器直接选集
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(0)
                    if (holder != null) holder.itemView.requestFocus() else recycler.requestFocus()
                }

                if (episodes.isEmpty()) {
                    statusText.text = if (detail.title == seriesId) {
                        // 服务端没这个条目（已下架/无效 ID）：App 版与网页版都拿不到
                        // 标题，回退为传入的 seriesId
                        "该内容可能已下架或暂时无法观看"
                    } else {
                        getString(R.string.empty)
                    }
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                statusText.text = e.message ?: getString(R.string.load_failed)
                statusText.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun playEpisode(ep: NetClient.Episode) {
        // 统一按集号升序排序，vid 列表与集号列表按下标一一对应
        val sorted = episodes.sortedBy { it.index }
        val pos = sorted.indexOfFirst { it.vid == ep.vid }.coerceAtLeast(0)

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
        intent.putExtra(PlayerActivity.EXTRA_TITLE, seriesTitle)
        intent.putExtra(PlayerActivity.EXTRA_COVER, seriesCover)
        intent.putExtra(PlayerActivity.EXTRA_EPISODE_POS, pos)
        intent.putStringArrayListExtra(
            PlayerActivity.EXTRA_VID_LIST,
            ArrayList(sorted.map { it.vid })
        )
        intent.putIntegerArrayListExtra(
            PlayerActivity.EXTRA_EP_LIST,
            ArrayList(sorted.map { it.index })
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
    }
}
