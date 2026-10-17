package com.example.bluetoothtest

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

/** The call screen's audio output sheet: Phone speaker / Earpiece / the connected headset, the one in use checked. */
internal object CallOutputSheet {
    fun iconFor(output: CallOutput): Int = when (output) {
        CallOutput.SPEAKER -> R.drawable.ic_volume_up
        CallOutput.EARPIECE -> R.drawable.ic_phone_in_talk
        CallOutput.WIRED_HEADSET, CallOutput.BLUETOOTH -> R.drawable.ic_headphones
    }

    fun show(
        activity: AppCompatActivity,
        options: List<CallOutputOption>,
        current: CallOutput,
        onPick: (CallOutputOption) -> Unit,
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_call_output, null)
        val rows = view.findViewById<LinearLayout>(R.id.callOutputRows)
        val selected = CallOutputChoices.selected(options, current)
        options.forEach { option ->
            val row = LayoutInflater.from(activity).inflate(R.layout.item_output_row, rows, false)
            row.findViewById<ImageView>(R.id.outputIcon).setImageResource(iconFor(option.output))
            row.findViewById<TextView>(R.id.outputName).text = option.name
            val isSelected = option == selected
            row.findViewById<View>(R.id.outputCheck).visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            row.contentDescription = if (isSelected) {
                option.name + ", " + activity.getString(R.string.call_output_selected)
            } else {
                option.name
            }
            row.setOnClickListener {
                sheet.dismiss()
                onPick(option)
            }
            rows.addView(row)
        }
        sheet.setContentView(view)
        sheet.show()
        return sheet
    }
}
