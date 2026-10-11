package com.webdav.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webdav.music.R
import com.webdav.music.data.Track

class TrackAdapter : ListAdapter<Track, TrackAdapter.VH>(DIFF) {

    /** 点击回调 */
    var onItemClick: ((Track) -> Unit)? = null

    /** 暗色主题（横屏暗色背景时用白色文字布局） */
    var dark: Boolean = false

    /** 正在播放的曲目路径（列表高亮 + 同步位置） */
    var currentPath: String? = null
        private set

    /** 主题强调色（当前播放高亮），0 表示用默认 brand_accent */
    var accentColor: Int = 0

    fun setCurrentPath(path: String?) {
        if (currentPath == path) return
        // 精确刷新旧/新高亮项，避免全列表重绘
        val oldIdx = currentList.indexOfFirst { it.path == currentPath }
        currentPath = path
        val newIdx = currentList.indexOfFirst { it.path == path }
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvTrackName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (dark) R.layout.item_track_dark else R.layout.item_track
        val v = LayoutInflater.from(parent.context)
            .inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        // 显示名去掉音频格式后缀（.mp3/.flac 等）
        holder.name.text = t.name.replace(
            Regex("\\.(mp3|flac|wav|m4a|ogg|aac|wma|opus|ape)$", RegexOption.IGNORE_CASE), ""
        )
        val playing = t.path == currentPath
        val c = holder.itemView.context
        val accent = if (accentColor != 0) accentColor
            else ContextCompat.getColor(c, R.color.brand_accent)
        holder.name.setTextColor(if (playing) accent else ContextCompat.getColor(
            c, if (dark) android.R.color.white else R.color.text_primary))
        holder.itemView.setOnClickListener { onItemClick?.invoke(t) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.path == b.path
            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }
}
