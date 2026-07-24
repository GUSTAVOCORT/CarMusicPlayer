package com.carplayer.music.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.carplayer.music.R
import com.carplayer.music.databinding.ItemRowBinding
import com.carplayer.music.scanner.BrowserItem

class BrowserAdapter(
    private val onClick: (BrowserItem) -> Unit
) : ListAdapter<BrowserItem, BrowserAdapter.VH>(DIFF) {

    /** Indice de la pista sonando, para resaltarla sin recargar toda la lista. */
    var playingPath: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { i, item ->
                if (item is BrowserItem.Track &&
                    (item.song.path == old || item.song.path == value)
                ) notifyItemChanged(i, PAYLOAD_HIGHLIGHT)
            }
        }

    inner class VH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.b
        when (item) {
            is BrowserItem.UpDir -> {
                b.icon.setImageResource(R.drawable.ic_up)
                b.title.text = holder.itemView.context.getString(R.string.up_dir)
                b.subtitle.text = ""
            }
            is BrowserItem.Folder -> {
                b.icon.setImageResource(R.drawable.ic_folder)
                b.title.text = item.name
                b.subtitle.text = holder.itemView.context
                    .getString(R.string.tracks_count, item.count)
            }
            is BrowserItem.Track -> {
                b.icon.setImageResource(R.drawable.ic_note)
                b.title.text = item.song.title
                b.subtitle.text = item.song.artist.ifBlank { item.song.folderName }
            }
        }
        applyHighlight(holder, item)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
            applyHighlight(holder, getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun applyHighlight(holder: VH, item: BrowserItem) {
        val active = item is BrowserItem.Track && item.song.path == playingPath
        holder.b.root.isActivated = active
        // La cancion que suena tambien resalta en el texto y el icono
        val ctx = holder.itemView.context
        if (active) {
            val accent = ContextCompat.getColor(ctx, R.color.accent)
            holder.b.title.setTextColor(accent)
            holder.b.icon.setColorFilter(accent)
        } else {
            holder.b.title.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            holder.b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent))
        }
    }

    companion object {
        private const val PAYLOAD_HIGHLIGHT = "hl"

        private val DIFF = object : DiffUtil.ItemCallback<BrowserItem>() {
            override fun areItemsTheSame(a: BrowserItem, b: BrowserItem): Boolean = when {
                a is BrowserItem.Track && b is BrowserItem.Track -> a.song.path == b.song.path
                a is BrowserItem.Folder && b is BrowserItem.Folder -> a.path == b.path
                a is BrowserItem.UpDir && b is BrowserItem.UpDir -> true
                else -> false
            }

            override fun areContentsTheSame(a: BrowserItem, b: BrowserItem): Boolean = a == b
        }
    }
}
