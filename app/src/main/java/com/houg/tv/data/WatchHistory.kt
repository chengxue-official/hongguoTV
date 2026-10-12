package com.houg.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 追剧历史：基于 SharedPreferences 持久化，记录最近观看的剧集与进度。
 * 兼容 API 14，使用系统自带 org.json。
 */
class WatchHistory(context: Context) {

    data class Entry(
        val seriesId: String,
        val title: String,
        val cover: String,
        val lastEp: Int,
        val time: Long
    )

    private val prefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
    private val lock = Any()

    /** 记录/更新一部剧的观看进度 */
    fun record(seriesId: String, title: String, cover: String, ep: Int) {
        synchronized(lock) {
            val map = loadAll().toMutableMap()
            map[seriesId] = Entry(seriesId, title, cover, ep, System.currentTimeMillis())
            saveAll(map)
        }
    }

    /** 上次看到第几集（没看过返回 0） */
    fun lastEpisode(seriesId: String): Int {
        synchronized(lock) {
            return loadAll()[seriesId]?.lastEp ?: 0
        }
    }

    /** 追剧列表（按最近观看倒序） */
    fun list(): List<Entry> {
        synchronized(lock) {
            return loadAll().values.sortedByDescending { it.time }
        }
    }

    fun remove(seriesId: String) {
        synchronized(lock) {
            val map = loadAll().toMutableMap()
            map.remove(seriesId)
            saveAll(map)
        }
    }

    private fun loadAll(): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        val raw = prefs.getString(KEY, null) ?: return result
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                result[id] = Entry(
                    id,
                    o.optString("title"),
                    o.optString("cover"),
                    o.optInt("ep", 0),
                    o.optLong("time", 0L)
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun saveAll(map: Map<String, Entry>) {
        val arr = JSONArray()
        for (e in map.values) {
            val o = JSONObject()
            o.put("id", e.seriesId)
            o.put("title", e.title)
            o.put("cover", e.cover)
            o.put("ep", e.lastEp)
            o.put("time", e.time)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
