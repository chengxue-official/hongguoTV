package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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

/** 详情页：封面 + 简介 + 收藏按钮 + 选集（带续播定位） */
class DetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var seriesId: String
    private lateinit var seriesTitle: String
    private lateinit var seriesCover: String

    private var episodes: List<NetClient.Episode> = emptyList()
    private var lastEp = 0

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

        updateFavoriteButton()
        findViewById<View>(R.id.btn_favorite).setOnClickListener { toggleFavorite() }

        loadDetail()
    }

    private fun toggleFavorite() {
        val now = App.watchHistory.toggleFavorite(seriesId, seriesTitle, seriesCover)
        updateFavoriteButton()
        Toast.makeText(
            this,
            if (now) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteButton() {
        val btn = findViewById<TextView>(R.id.btn_favorite)
        val fav = seriesId.isNotEmpty() && App.watchHistory.isFavorite(seriesId)
        btn.text = if (fav) getString(R.string.favorited) else getString(R.string.favorite)
        btn.isSelected = fav
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
                // 详情接口拿到的标题/封面比列表页更准，回填给收藏与历史用
                if (detail.title.isNotEmpty()) seriesTitle = detail.title
                if (detail.cover.isNotEmpty()) seriesCover = detail.cover
                val meta = findViewById<TextView>(R.id.detail_meta)
                val countText = getString(R.string.episode_count, episodes.size.toString())
                meta.text = countText
                val intro = findViewById<TextView>(R.id.detail_intro)
                intro.text = detail.intro
                if (detail.cover.isNotEmpty()) {
                    Glide.with(this@DetailActivity).load(detail.cover)
                        .placeholder(android.R.color.darker_gray)
                        .error(android.R.color.darker_gray)
                        .into(findViewById(R.id.detail_cover))
                }

                lastEp = App.watchHistory.lastEpisode(seriesId)
                // 详情接口拿到的标题/封面更权威，若已收藏则**原地**刷新收藏项元数据
                // （不动收藏时间，避免"只是看了下详情"就把该剧顶到收藏列表最前）
                App.watchHistory.updateFavoriteMeta(seriesId, seriesTitle, seriesCover)

                // 续播入口：有历史进度时在选集上方显示「继续观看 第N集」
                val resumeBtn = findViewById<TextView>(R.id.btn_resume)
                if (lastEp > 0 && episodes.isNotEmpty()) {
                    resumeBtn.text = getString(R.string.continue_ep, lastEp.toString())
                    resumeBtn.visibility = View.VISIBLE
                    resumeBtn.setOnClickListener { resumePlay() }
                } else {
                    resumeBtn.visibility = View.GONE
                }

                val episodeAdapter = EpisodeAdapter(episodes, lastEp) { ep -> playEpisode(ep) }
                val recycler = findViewById<RecyclerView>(R.id.episode_recycler)
                recycler.layoutManager = GridLayoutManager(
                    this@DetailActivity, adaptiveGridColumns(this@DetailActivity, 100, 4, 12))
                recycler.adapter = episodeAdapter
                // 焦点：优先落在「继续观看」，否则落在上次看的集，最后才落第 1 集
                recycler.post {
                    if (lastEp > 0 && resumeBtn.visibility == View.VISIBLE) {
                        resumeBtn.requestFocus()
                    } else {
                        val idx = episodes.indexOfFirst { it.index == lastEp }.coerceAtLeast(0)
                        val holder = recycler.findViewHolderForAdapterPosition(idx)
                        if (holder != null) holder.itemView.requestFocus() else recycler.requestFocus()
                    }
                }
                updateFavoriteButton()

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
                // 「已下架」类业务异常直接显示；网络类异常显示通用重试文案
                val msg = e.message ?: ""
                statusText.text = if (msg.contains("下架")) msg
                    else getString(R.string.load_failed)
                statusText.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 「继续观看」：直接进入上次那集，播放器会自动 seek 到上次位置。 */
    private fun resumePlay() {
        val ep = episodes.firstOrNull { it.index == lastEp } ?: episodes.firstOrNull() ?: return
        playEpisode(ep)
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
