package com.example.bluetoothtest

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The "name this contact" dialog shared by the verify flow ("Save contact as", blank keeps the handle) and
 * the Contacts screen (rename, blank not allowed). The text is checked live with [ContactNames.check]; the
 * positive button stays off until the name can be saved. [onSave] gets the canonical name, or "" for blank.
 */
internal object ContactNameDialog {
    fun show(
        context: Context,
        title: Int,
        message: Int,
        initial: String,
        allowBlank: Boolean,
        positive: Int,
        negative: Int,
        onSave: (String) -> Unit,
        onDismissWithoutSave: () -> Unit = {},
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        val field = TextInputLayout(context).apply {
            hint = context.getString(R.string.contact_name_hint)
            isCounterEnabled = false
        }
        val input = TextInputEditText(field.context).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        field.addView(input)
        val container = FrameLayout(context).apply {
            val side = (20 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(field)
        }
        var saved = false
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(positive, null)
            .setNegativeButton(negative, null)
            .setOnDismissListener { if (!saved) onDismissWithoutSave() }
            .create()
        val save = {
            val check = ContactNames.check(input.text?.toString().orEmpty())
            when (ContactNameRules.onSave(check, allowBlank)) {
                ContactNameRules.SaveAction.SAVE -> {
                    saved = true
                    dialog.dismiss()
                    onSave((check as ContactNameCheck.Valid).name)
                }
                ContactNameRules.SaveAction.SAVE_BLANK -> {
                    saved = true
                    dialog.dismiss()
                    onSave("")
                }
                // UX fix 2026-10-21: an empty name explains itself under the field; typing clears it.
                ContactNameRules.SaveAction.SHOW_EMPTY_ERROR -> field.error = context.getString(R.string.contact_name_empty)
                ContactNameRules.SaveAction.NONE -> Unit
            }
        }
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val refresh = {
                when (ContactNames.check(input.text?.toString().orEmpty())) {
                    is ContactNameCheck.Valid -> {
                        field.error = null
                        button.isEnabled = true
                    }
                    ContactNameCheck.Blank -> {
                        field.error = null
                        button.isEnabled = ContactNameRules.buttonEnabled(ContactNameCheck.Blank, allowBlank)
                    }
                    is ContactNameCheck.Invalid -> {
                        field.error = context.getString(R.string.contact_name_invalid)
                        button.isEnabled = false
                    }
                }
            }
            button.setOnClickListener { save() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && button.isEnabled) save()
                true
            }
            refresh()
        }
        dialog.show()
        return dialog
    }
}
