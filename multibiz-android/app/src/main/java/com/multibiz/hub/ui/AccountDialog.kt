package com.multibiz.hub.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.multibiz.hub.R
import com.multibiz.hub.core.Account

object AccountDialog {
    val COLORS = intArrayOf(
        0xFF00796B.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFC62828.toInt(),
        0xFFEF6C00.toInt(), 0xFF2E7D32.toInt(), 0xFF37474F.toInt(), 0xFFAD1457.toInt()
    )

    /** [onSave] returns an error message to keep the dialog open, or null on success. */
    fun show(activity: AppCompatActivity, existing: Account?, suggestedColor: Int, onSave: (String, Int, String) -> String?) {
        val v = activity.layoutInflater.inflate(R.layout.dialog_account, null)
        val layout = v.findViewById<TextInputLayout>(R.id.nameLayout)
        val input = v.findViewById<TextInputEditText>(R.id.nameInput)
        val group = v.findViewById<ChipGroup>(R.id.colorGroup)
        val descInput = v.findViewById<TextInputEditText>(R.id.descInput)
        val selected = existing?.color ?: suggestedColor

        COLORS.forEach { c ->
            val chip = Chip(activity).apply {
                id = View.generateViewId()
                isCheckable = true
                isCheckedIconVisible = true
                checkedIconTint = ColorStateList.valueOf(Color.WHITE)
                chipBackgroundColor = ColorStateList.valueOf(c)
                text = "  "
                tag = c
            }
            group.addView(chip)
            if (c == selected) chip.isChecked = true
        }
        if (group.checkedChipId == View.NO_ID) (group.getChildAt(0) as Chip).isChecked = true

        existing?.let {
            input.setText(it.name)
            input.setSelection(it.name.length)
            descInput.setText(it.description)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.add_account else R.string.edit_account)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                val color = group.findViewById<Chip>(group.checkedChipId)?.tag as? Int ?: COLORS[0]
                val desc = descInput.text?.toString()?.trim().orEmpty()
                val err = onSave(name, color, desc)
                if (err == null) dialog.dismiss() else layout.error = err
            }
        }
        dialog.show()
    }
}
