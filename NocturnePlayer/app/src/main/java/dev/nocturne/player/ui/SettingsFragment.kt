package dev.nocturne.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.nocturne.player.NocturneApp
import dev.nocturne.player.R
import dev.nocturne.player.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private val app get() = requireActivity().application as NocturneApp
    private var preferences = Preferences()
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) lifecycleScope.launch {
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.database.dao().putFolder(ScanFolder(uri.toString(), androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: "Папка"))
            Toast.makeText(requireContext(), "Папка добавлена. Обновите видеотеку.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show() }
    } }
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24); setBackgroundColor(requireContext().getColor(R.color.page)) }
        val scroll = ScrollView(requireContext()).apply { addView(box) }
        lifecycleScope.launch {
            preferences = app.preferences.values.first()
            fun button(text: String, action: () -> Unit) { box.addView(MaterialButton(requireContext()).apply { this.text = text; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }) }
            fun toggle(text: String, checked: Boolean, update: (Boolean) -> Unit) { box.addView(MaterialSwitch(requireContext()).apply { this.text = text; isChecked = checked; setPadding(8); setOnCheckedChangeListener { _, value -> update(value); persist() } }) }
            button("‹  Видеотека") { findNavController().popBackStack() }
            box.addView(TextView(requireContext()).apply { text = "Настройки"; textSize = 30f; setTextColor(requireContext().getColor(R.color.ink)); setPadding(8) })
            toggle("Жесты управления", preferences.gestures) { preferences = preferences.copy(gestures = it) }
            toggle("Автоматически следующее видео", preferences.autoNext) { preferences = preferences.copy(autoNext = it) }
            button("Скорость по умолчанию") { val speeds = floatArrayOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
                MaterialAlertDialogBuilder(requireContext()).setItems(speeds.map { "${it}×" }.toTypedArray()) { _, i -> preferences = preferences.copy(speed = speeds[i]); persist() }.show() }
            button("Оформление субтитров по умолчанию") {
                SubtitleStyleDialog.show(requireContext(), VideoOptions("defaults", textSize = preferences.subtitleSize, textColor = preferences.color,
                    background = preferences.background, font = preferences.font, bottom = preferences.bottom, preserveStyles = preferences.preserve)) {
                    preferences = preferences.copy(subtitleSize = it.textSize, color = it.textColor, background = it.background, font = it.font, bottom = it.bottom, preserve = it.preserveStyles); persist()
                }
            }
            button("Тема оформления") { MaterialAlertDialogBuilder(requireContext()).setItems(arrayOf("Тёмная", "Светлая", "Как в системе")) { _, i -> preferences = preferences.copy(theme = arrayOf("dark", "light", "system")[i]); persist() }.show() }
            button("Добавить папку для сканирования / USB") { folder.launch(null) }
            button("Управление папками сканирования") { lifecycleScope.launch {
                val folders = app.database.dao().folderList()
                MaterialAlertDialogBuilder(requireContext()).setTitle("Убрать папку из сканирования").setItems(folders.map { it.name }.toTypedArray()) { _, i -> lifecycleScope.launch {
                    app.database.dao().removeFolder(folders[i].uri)
                    // Keep the grant: imported videos and subtitle links may still depend on it.
                } }.setNegativeButton("Закрыть", null).show()
            } }
            button("Очистить историю просмотра") { MaterialAlertDialogBuilder(requireContext()).setMessage("Удалить позиции и историю всех видео?").setPositiveButton("Очистить") { _, _ -> lifecycleScope.launch { app.database.dao().clearHistory() } }.setNegativeButton("Отмена", null).show() }
            box.addView(TextView(requireContext()).apply { text = "Nocturne 1.0\nЛокальная видеотека · AndroidX Media3\n\nВиртуальные папки группируют видео внутри приложения. Подключите физические папки через SAF для поиска видео и соседних субтитров."; setTextColor(requireContext().getColor(R.color.muted)); setPadding(12) })
        }
        return scroll
    }
    private fun persist() { lifecycleScope.launch { app.preferences.save(preferences) } }
}
