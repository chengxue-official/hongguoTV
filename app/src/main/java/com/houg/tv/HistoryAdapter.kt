package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.data.WatchHistory

/** 追剧历史列表 Adapter */
class HistoryAdapter(
    private val onItemClick: (WatchHistory.Entry) -> Unit,
    private val onLongClick: (WatchHistory.Entry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = ArrayList<WatchHistory.Entry>()

    fun submit(list: List<WatchHistory.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val progress: TextView = view.findViewById(R.id.progress)

        fun bind(e: WatchHistory.Entry) {
            title.text = e.title
            progress.text = "看到第 ${e.lastEp} 集"
            if (e.cover.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(e.cover)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(cover)
            }
            itemView.setOnClickListener { onItemClick(e) }
            itemView.setOnLongClickListener {
                onLongClick(e)
                true
            }
        }
    }
}
