package com.flashing.tool.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.flashing.tool.R
import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.model.PartitionSafety
import com.flashing.tool.model.RiskLevel

enum class PgptUiMode { BACKUP, WRITE, SCATTER }

data class DevicePartRow(
    val entry: PartitionEntry,
    var selected: Boolean = false,
    var imageUri: Uri? = null,
    var imageName: String? = null,
    var imageSize: Long = 0L,
)

class DevicePartitionAdapter(
    private val context: Context,
    private val allRows: MutableList<DevicePartRow>,
    private var mode: PgptUiMode,
    private val onChanged: () -> Unit,
    private val onPickFile: (absoluteIndex: Int) -> Unit,
) : BaseAdapter() {

    private var query: String = ""
    private var visible: List<DevicePartRow> = allRows.toList()

    fun setMode(m: PgptUiMode) {
        mode = m
        notifyDataSetChanged()
    }

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
                    (it.imageName?.lowercase()?.contains(q) == true) ||
                    (it.entry.region?.lowercase()?.contains(q) == true)
            }
        }
    }

    /** Map visible position → index in allRows (for pick file). */
    fun absoluteIndex(visiblePos: Int): Int {
        val row = visible.getOrNull(visiblePos) ?: return -1
        return allRows.indexOf(row)
    }

    override fun getCount() = visible.size
    override fun getItem(position: Int) = visible[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_device_partition_row, parent, false)
        val row = visible[position]
        val cb = v.findViewById<CheckBox>(R.id.cbSelect)
        val tvName = v.findViewById<TextView>(R.id.tvName)
        val tvMeta = v.findViewById<TextView>(R.id.tvMeta)
        val indicator = v.findViewById<View>(R.id.riskIndicator)
        val btnPick = v.findViewById<Button>(R.id.btnPickFile)

        cb.setOnCheckedChangeListener(null)
        cb.isChecked = row.selected
        cb.setOnCheckedChangeListener { _, checked ->
            row.selected = checked
            onChanged()
        }

        tvName.text = row.entry.name
        tvMeta.text = buildString {
            append("%.1f MB".format(row.entry.sizeMb))
            append("  ·  sec ${row.entry.startSector}")
            if (mode == PgptUiMode.WRITE) {
                if (row.imageName != null) {
                    append("\n→ ${row.imageName}")
                    if (row.imageSize > 0) append(" (%.1f MB)".format(row.imageSize / 1024.0 / 1024.0))
                } else {
                    append("\n→ (belum pilih file)")
                }
            }
        }

        val color = when (PartitionSafety.riskLevel(row.entry.name)) {
            RiskLevel.CRITICAL -> 0xFFFF5370.toInt()
            RiskLevel.HIGH -> 0xFFFFB74D.toInt()
            RiskLevel.MEDIUM -> 0xFF7C4DFF.toInt()
            RiskLevel.NORMAL -> 0xFF2A2638.toInt()
        }
        indicator.setBackgroundColor(color)

        if (mode == PgptUiMode.WRITE) {
            btnPick.visibility = View.VISIBLE
            btnPick.setOnClickListener {
                val abs = absoluteIndex(position)
                if (abs >= 0) onPickFile(abs)
            }
        } else {
            btnPick.visibility = View.GONE
            btnPick.setOnClickListener(null)
        }

        v.setOnClickListener {
            row.selected = !row.selected
            notifyDataSetChanged()
            onChanged()
        }
        return v
    }

    fun selectAllVisible() {
        visible.forEach { it.selected = true }
        notifyDataSetChanged()
        onChanged()
    }

    fun unselectAllVisible() {
        visible.forEach { it.selected = false }
        notifyDataSetChanged()
        onChanged()
    }

    fun selectAll() {
        allRows.forEach { it.selected = true }
        notifyDataSetChanged()
        onChanged()
    }

    fun unselectAll() {
        allRows.forEach { it.selected = false }
        notifyDataSetChanged()
        onChanged()
    }

    fun selected(): List<DevicePartRow> = allRows.filter { it.selected }

    fun visibleCount(): Int = visible.size
}
