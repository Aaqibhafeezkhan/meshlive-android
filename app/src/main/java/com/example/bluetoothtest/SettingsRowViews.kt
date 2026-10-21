package com.example.bluetoothtest

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * A settings label whose info mark (i) follows its last word (S22 review 2026-10-21). The mark used to be the
 * TextView's drawableEnd, so a label that wrapped to two lines filled the width and the mark sat at the far right,
 * against the switch. The mark is now an image span after a non-breaking space: it wraps with the last word, keeps the
 * old size, tint and gap, and the whole label stays the tap target for the explanation. A MaterialTextView, so the row
 * label's text appearance line height applies as on the other labels.
 */
class InfoLabelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : com.google.android.material.textview.MaterialTextView(context, attrs, defStyleAttr) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(InfoMark.withMark(context, text), BufferType.SPANNABLE)
    }
}

/** Appends the info mark to a label (once). */
internal object InfoMark {
    private class MarkSpan(drawable: android.graphics.drawable.Drawable) : ImageSpan(drawable, ALIGN_CENTER)

    fun withMark(context: Context, text: CharSequence?): CharSequence {
        val base = text ?: return ""
        if (base.isEmpty()) return base
        if (base is Spanned && base.getSpans(0, base.length, MarkSpan::class.java).isNotEmpty()) return base
        val icon = AppCompatResources.getDrawable(context, R.drawable.ic_info_small)?.mutate() ?: return base
        icon.setTint(ContextCompat.getColor(context, R.color.text_secondary))
        val gap = (6 * context.resources.displayMetrics.density).toInt()
        val mark = InsetDrawable(icon, gap, 0, 0, 0).apply { setBounds(0, 0, icon.intrinsicWidth + gap, icon.intrinsicHeight) }
        return SpannableStringBuilder(base).append(' ').also { builder ->
            builder.setSpan(MarkSpan(mark), builder.length - 1, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/**
 * A row's title and its value (S22 review 2026-10-21: "Who sees my name  Everyone I call or answer" was squeezed onto
 * one line). The value sits at the end of the title's line when both fit, and moves under the title as a second line,
 * like Android Settings' summary, when they do not. Child 0 is the title, child 1 the value.
 */
class TitleValueLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val density = context.resources.displayMetrics.density
    private val gapPx = (16 * density).toInt()
    private val lineGapPx = (2 * density).toInt()
    /** The value's old 4 dp end margin, before the chevron. */
    private val endGapPx = (4 * density).toInt()

    /** True when the value is drawn under the title. */
    var stacked = false
        private set

    private fun valueShown(): Boolean {
        val value = getChildAt(1) ?: return false
        return value.visibility != View.GONE && !((value as? TextView)?.text.isNullOrEmpty())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val title = getChildAt(0)
        val value = getChildAt(1)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val bounded = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        if (title == null) {
            setMeasuredDimension(width, 0)
            return
        }
        if (value == null || !valueShown()) {
            value?.measure(free, free)
            title.measure(bounded, free)
            stacked = false
            setMeasuredDimension(width, title.measuredHeight)
            return
        }
        title.measure(free, free)
        value.measure(free, free)
        stacked = TitleValueFit.stack(title.measuredWidth, value.measuredWidth + endGapPx, gapPx, width)
        if (stacked) {
            title.measure(bounded, free)
            value.measure(bounded, free)
            setMeasuredDimension(width, title.measuredHeight + lineGapPx + value.measuredHeight)
        } else {
            setMeasuredDimension(width, max(title.measuredHeight, value.measuredHeight))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        fun place(child: View, x: Int, y: Int) {
            val left = if (rtl) width - x - child.measuredWidth else x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
        }
        val title = getChildAt(0) ?: return
        val value = getChildAt(1)
        if (value == null || !valueShown()) {
            place(title, 0, (height - title.measuredHeight) / 2)
            return
        }
        if (stacked) {
            place(title, 0, 0)
            place(value, 0, title.measuredHeight + lineGapPx)
        } else {
            place(title, 0, (height - title.measuredHeight) / 2)
            place(value, width - value.measuredWidth - endGapPx, (height - value.measuredHeight) / 2)
        }
    }
}

/** The pure rule of [TitleValueLayout]: stack the value under the title when the two do not fit side by side. */
internal object TitleValueFit {
    fun stack(titleWidth: Int, valueWidth: Int, gap: Int, available: Int): Boolean = titleWidth + gap + valueWidth > available
}
