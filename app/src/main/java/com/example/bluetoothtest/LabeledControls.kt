package com.example.bluetoothtest

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.material.button.MaterialButton

/**
 * A round control whose small label under it follows its visibility. The activity shows and hides these
 * buttons in many places; mirroring it here keeps a label from outliving its button.
 */
internal class LabeledImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle,
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    var label: View? = null
        set(value) {
            field = value
            value?.visibility = visibility
        }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        label?.visibility = visibility
    }
}

/** The Go live / End button: its label ("End") shows only while [labelWanted] and the button is visible. */
internal class LabeledMaterialButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    var label: View? = null
        set(value) {
            field = value
            syncLabel()
        }
    var labelWanted: Boolean = false
        set(value) {
            field = value
            syncLabel()
        }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        syncLabel()
    }

    private fun syncLabel() {
        label?.visibility = if (visibility == View.VISIBLE && labelWanted) View.VISIBLE else View.GONE
    }
}

/** A [MaterialButton] round control (the same look as the call controls) whose label follows its visibility. */
internal class LabeledRoundButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    var label: View? = null
        set(value) {
            field = value
            value?.visibility = visibility
        }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        label?.visibility = visibility
    }
}
