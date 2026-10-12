package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索记录胶囊 Adapter：自动换行排列关键词。
 * 点击 = 用该词重新搜索；长按 = 删除该条。
 */
class SearchChipAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<SearchChipAdapter.VH>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_chip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val kw = items[position]
        holder.text.text = kw
        holder.text.setOnClickListener { onClick(kw) }
        holder.text.setOnLongClickListener {
            onLongClick(kw)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }
}
