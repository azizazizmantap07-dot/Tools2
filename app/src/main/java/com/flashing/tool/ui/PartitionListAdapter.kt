package com.flashing.tool.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.flashing.tool.R
import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.model.PartitionSafety
import com.flashing.tool.model.RiskLevel

/**
 * Renders each partition with a colored risk indicator bar so the user sees
 * at a glance which partitions are safe to experiment with vs. which ones
 * (CRITICAL/HIGH per PartitionSafety) need real caution before writing.
 */
class PartitionListAdapter(
    context: Context,
    private val entries: List<PartitionEntry>,
) : ArrayAdapter<PartitionEntry>(context, R.layout.item_partition, entries) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_partition, parent, false)
        val entry = entries[position]

        val tvName = view.findViewById<TextView>(R.id.tvPartitionName)
        val tvMeta = view.findViewById<TextView>(R.id.tvPartitionMeta)
        val indicator = view.findViewById<View>(R.id.riskIndicator)

        tvName.text = entry.name
        tvMeta.text = "%.1f MB  •  sektor %d".format(entry.sizeMb, entry.startSector)

        val color = when (PartitionSafety.riskLevel(entry.name)) {
            RiskLevel.CRITICAL -> 0xFFFF5370.toInt() // danger_red
            RiskLevel.HIGH -> 0xFFFFB74D.toInt()      // warn_amber
            RiskLevel.MEDIUM -> 0xFF7C4DFF.toInt()    // accent_purple
            RiskLevel.NORMAL -> 0xFF2A2638.toInt()    // neutral, subtle
        }
        indicator.setBackgroundColor(color)

        return view
    }
}
