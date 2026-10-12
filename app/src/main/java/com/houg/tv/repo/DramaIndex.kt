package com.houg.tv.repo

import android.content.Context
import com.houg.tv.net.NetClient
import com.houg.tv.util.AppLog
import com.houg.tv.util.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地片名索引：拉取红果全量片单（3 个分类），把剧名转成拼音首字母，
 * 供「首字母搜索」本地匹配。索引持久化到 filesDir，避免每次冷启动重拉。
 */
object DramaIndex {

    private const val FILE = "drama_index.json"
    private const val TAG = "DramaIndex"
    /** 每个分类最多翻的页数（每页 18 条） */
    private const val MAX_PAGES_PER_GENRE = 60

    /** 片名索引项 */
    data class Entry(val id: String, val title: String, val cover: String, val initials: String)

    /** 三个分类（对齐 MainActivity 的 genres） */
    private val GENRES = listOf(
        Pair("short_play", "default"),
        Pair("comic_series", "comic_series"),
        Pair("ai_series", "ai_series"),
    )

    @Volatile
    private var entries: List<Entry>? = null

    /** 读索引（内存 → 磁盘 → 触发后台刷新）。可能返回空列表（尚未建立）。 */
    fun load(context: Context): List<Entry> {
        entries?.let { return it }
        val fromDisk = readFromDisk(context)
        entries = fromDisk
        return fromDisk
    }

    /**
     * 从服务端拉全量并持久化，返回新索引。在 IO 线程调用。
     * 任一分类失败不中断其它分类。
     */
    suspend fun rebuild(context: Context, netClient: NetClient): List<Entry> = withContext(Dispatchers.IO) {
        val list = ArrayList<Entry>()
        for ((genre, scene) in GENRES) {
            try {
                val dramas = netClient.catalogAll(genre, scene, MAX_PAGES_PER_GENRE)
                for (d in dramas) {
                    val initials = Pinyin.firstLetters(d.title)
                    if (initials.isNotEmpty()) {
                        list.add(Entry(d.id, d.title, d.cover, initials))
                    }
                }
                AppLog.d(TAG, "genre=$genre 索引 " + dramas.size + " 条")
            } catch (e: Exception) {
                AppLog.e(TAG, "genre=$genre 拉取失败", e)
            }
        }
        val deduped = list.distinctBy { it.id }
        writeToDisk(context, deduped)
        entries = deduped
        AppLog.d(TAG, "索引重建完成，共 " + deduped.size + " 条")
        deduped
    }

    /** 是否已有可用索引。 */
    fun isReady(): Boolean = entries != null && entries!!.isNotEmpty()

    /**
     * 按输入匹配：纯字母 → 首字母前缀匹配；含中文 → 标题包含匹配。
     * @param limit 最大返回条数
     */
    fun search(input: String, limit: Int = 50): List<Entry> {
        val e = entries ?: return emptyList()
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val isLatin = q.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        val upper = q.uppercase()
        val result = ArrayList<Entry>()
        for (item in e) {
            val hit = if (isLatin) {
                item.initials.startsWith(upper) || item.initials.contains(upper)
            } else {
                item.title.contains(q)
            }
            if (hit) result.add(item)
            if (result.size >= limit) break
        }
        return result
    }

    private fun readFromDisk(context: Context): List<Entry> {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val json = JSONArray(f.readText())
            val list = ArrayList<Entry>(json.length())
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val title = o.optString("title")
                val cover = o.optString("cover")
                val initials = o.optString("initials")
                if (id.isNotEmpty() && title.isNotEmpty()) {
                    list.add(Entry(id, title, cover, initials))
                }
            }
            list
        } catch (e: Exception) {
            AppLog.e(TAG, "读取索引失败", e)
            emptyList()
        }
    }

    private fun writeToDisk(context: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("title", e.title)
                o.put("cover", e.cover)
                o.put("initials", e.initials)
                arr.put(o)
            }
            File(context.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
            AppLog.e(TAG, "写索引失败", e)
        }
    }
}
