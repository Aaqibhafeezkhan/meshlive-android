package com.example.bluetoothtest

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The two-way switch of `view_segments.xml`. [selectedFirst] picks the white pill; [onFirst] and
 * [onSecond] run when the other side is tapped (the selected side does nothing).
 */
internal object SegmentedControl {
    fun bind(
        root: View,
        firstLabel: Int,
        secondLabel: Int,
        selectedFirst: Boolean,
        onFirst: () -> Unit,
        onSecond: () -> Unit,
    ) {
        val first = root.findViewById<TextView>(R.id.segFirst)
        val second = root.findViewById<TextView>(R.id.segSecond)
        first.setText(firstLabel)
        second.setText(secondLabel)
        style(first, selectedFirst)
        style(second, !selectedFirst)
        first.setOnClickListener { if (!selectedFirst) onFirst() }
        second.setOnClickListener { if (selectedFirst) onSecond() }
    }

    private fun style(view: TextView, selected: Boolean) {
        val context = view.context
        view.setBackgroundResource(if (selected) R.drawable.segment_selected else 0)
        if (!selected) view.setBackgroundResource(0)
        view.setTextColor(ContextCompat.getColor(context, if (selected) R.color.black else R.color.text_secondary))
        view.isSelected = selected
    }
}
