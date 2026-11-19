package dev.nocturne.player.ui

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.nocturne.player.data.VideoOptions

object SubtitleStyleDialog {
    fun show(context: Context, original: VideoOptions, save: (VideoOptions) -> Unit) {
        var draft = original
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 20) }
        fun label(text: String) = TextView(context).apply { this.text = text; setPadding(0, 18, 0, 8); box.addView(this) }
        fun slider(title: String, value: Float, min: Float, max: Float, step: Float, change: (Float) -> Unit) {
            val l = label("$title: ${"%.0f".format(value)}")
            box.addView(Slider(context).apply { valueFrom = min; valueTo = max; stepSize = step; this.value = value.coerceIn(min, max); addOnChangeListener { _, v, _ -> l.text = "$title: ${"%.0f".format(v)}"; change(v) } })
        }
        box.addView(MaterialSwitch(context).apply { text = "Сохранять оформление файла"; isChecked = draft.preserveStyles; setOnCheckedChangeListener { _, v -> draft = draft.copy(preserveStyles = v) } })
        label("Выключите сохранение оформления, чтобы заменить цвет, размер, шрифт и позицию из файла.")
        slider("Размер текста, sp", draft.textSize, 12f, 48f, 1f) { draft = draft.copy(textSize = it) }
        slider("Отступ снизу, %", (draft.bottom * 100).toInt().toFloat(), 0f, 45f, 1f) { draft = draft.copy(bottom = it / 100) }
        val foreground = EditText(context).apply { setSingleLine(); hint = "Цвет текста #RRGGBB"; setText(String.format("#%06X", draft.textColor and 0xFFFFFF)) }
        val background = EditText(context).apply { setSingleLine(); hint = "Цвет фона #RRGGBB"; setText(String.format("#%06X", draft.background and 0xFFFFFF)) }
        label("Цвет текста"); box.addView(foreground); label("Цвет фона"); box.addView(background)
        slider("Непрозрачность фона, %", ((Color.alpha(draft.background) / 255f) * 100).toInt().toFloat(), 0f, 100f, 1f) {
            draft = draft.copy(background = (draft.background and 0xFFFFFF) or ((it * 255 / 100).toInt() shl 24))
        }
        label("Шрифт")
        val fonts = arrayOf("sans-serif", "serif", "monospace", "sans-serif-condensed", "sans-serif-light")
        val font = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fonts); setSelection(fonts.indexOf(draft.font).coerceAtLeast(0)) }
        box.addView(font)
        val scroll = ScrollView(context).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Оформление субтитров").setView(scroll).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            try {
                val fg = Color.parseColor(foreground.text.toString())
                val bg = Color.parseColor(background.text.toString())
                save(draft.copy(textColor = fg, background = (bg and 0xFFFFFF) or (draft.background and 0xFF000000.toInt()), font = fonts[font.selectedItemPosition]))
                dialog.dismiss()
            } catch (_: IllegalArgumentException) { foreground.error = "Цвет в формате #RRGGBB"; background.error = "Проверьте цвет" }
        } }; dialog.show()
    }
}
