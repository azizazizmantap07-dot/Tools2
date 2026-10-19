package com.flashing.tool.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.flashing.tool.R
import com.flashing.tool.model.PartitionEntry

data class ScatterRow(
    val entry: PartitionEntry,
    var file: DocumentFile? = null,
    var selected: Boolean = false,
) {
    val fileFound: Boolean get() = file != null && file!!.exists()
}

class ScatterListAdapter(
    private val context: Context,
    private val allRows: MutableList<ScatterRow>,
    private val onSelectionChanged: () -> Unit,
) : BaseAdapter() {

    private var query: String = ""
    private var visible: List<ScatterRow> = allRows.toList()

    fun setFilter(q: String) {
        query = q.trim()
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun rebuildVisible() {
        val q = query.lowercase()
        visible = if (q.isEmpty()) {
            allRows.toList()
        } else {
            allRows.filter {
                it.entry.name.lowercase().contains(q) ||
                    (it.entry.imagePath?.lowercase()?.contains(q) == true) ||
                    (it.entry.region?.lowercase()?.contains(q) == true)
            }
        }
    }

    override fun getCount() = visible.size
    override fun getItem(position: Int) = visible[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_scatter_row, parent, false)
        val row = visible[position]

        val cb = view.findViewById<CheckBox>(R.id.cbSelect)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
        val tvStatus = view.findViewById<TextView>(R.id.tvFileStatus)

        cb.setOnCheckedChangeListener(null)
        cb.isEnabled = row.fileFound
        cb.isChecked = row.selected && row.fileFound
        cb.setOnCheckedChangeListener { _, isChecked ->
            if (!row.fileFound) {
                cb.isChecked = false
                return@setOnCheckedChangeListener
            }
            row.selected = isChecked
            onSelectionChanged()
        }

        tvName.text = row.entry.name
        val img = row.entry.imagePath ?: "—"
        val sizeMb = row.entry.sizeBytes / (1024.0 * 1024.0)
        val region = row.entry.region ?: ""
        tvMeta.text = buildString {
            append(img)
            append("  ·  %.1f MB".format(sizeMb))
            if (region.isNotEmpty()) append("  ·  $region")
            row.file?.length()?.let { len ->
                if (len > 0) append("  ·  file %.1f MB".format(len / (1024.0 * 1024.0)))
            }
        }

        if (row.fileFound) {
            tvStatus.text = "OK"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvStatus.text = "NO FILE"
            tvStatus.setTextColor(Color.parseColor("#F44336"))
        }

        view.setOnClickListener {
            if (row.fileFound) {
                row.selected = !row.selected
                notifyDataSetChanged()
                onSelectionChanged()
            }
        }
        return view
    }

    fun selectAllFound() {
        // Only visible + found when filter active; still mark all found if no filter
        val targets = if (query.isEmpty()) allRows else visible
        targets.forEach { if (it.fileFound) it.selected = true }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun unselectAll() {
        val targets = if (query.isEmpty()) allRows else visible
        targets.forEach { it.selected = false }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedRows(): List<ScatterRow> = allRows.filter { it.selected && it.fileFound }

    fun visibleCount(): Int = visible.size
}
