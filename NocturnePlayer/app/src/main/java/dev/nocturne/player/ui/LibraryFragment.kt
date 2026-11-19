package dev.nocturne.player.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.nocturne.player.NocturneApp
import dev.nocturne.player.R
import dev.nocturne.player.data.*
import dev.nocturne.player.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val vm: LibraryViewModel by viewModels()
    private val services get() = requireActivity().application as NocturneApp
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.scan() }
    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) {
        lifecycleScope.launch { try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val video = services.library.importVideo(uri)
            play(video, listOf(video))
        } catch (e: Exception) { notice(e.localizedMessage ?: "Не удалось открыть видео") } }
    } }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) {
        lifecycleScope.launch { try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            services.database.dao().putFolder(ScanFolder(uri.toString(), androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: "Папка"))
            vm.scan()
        } catch (e: Exception) { notice(e.localizedMessage ?: "Нет доступа к папке") } }
    } }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentLibraryBinding.bind(view)
        val adapter = VideoAdapter(viewLifecycleOwner.lifecycleScope, { play(it, vm.videos.value) }, ::videoMenu)
        b.list.layoutManager = GridLayoutManager(requireContext(), if (resources.configuration.screenWidthDp >= 700) 2 else 1)
        b.list.adapter = adapter
        b.search.doAfterTextChanged { vm.query.value = it.toString() }
        b.settings.setOnClickListener { findNavController().navigate(R.id.settings) }
        b.sort.setOnClickListener { MaterialAlertDialogBuilder(requireContext()).setTitle("Сортировка")
            .setSingleChoiceItems(arrayOf("Название", "Дата изменения", "Продолжительность"), vm.sort.value) { d, i -> vm.sort.value = i; d.dismiss() }.show() }
        b.more.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setItems(arrayOf("Открыть видео", "Добавить папку / USB", "Обновить видеотеку", "Создать плейлист", "Создать виртуальную папку")) { _, i ->
                when (i) { 0 -> open.launch(arrayOf("video/*", "application/octet-stream")); 1 -> folder.launch(null); 2 -> requestScan(); 3 -> createCollection("playlist"); 4 -> createCollection("folder") }
            }.show()
        }
        b.filters.setOnCheckedStateChangeListener { _, ids -> when (ids.firstOrNull()) {
            R.id.all -> vm.section.value = "all"
            R.id.resume -> vm.section.value = "continue"
            R.id.recent -> vm.section.value = "recent"
            R.id.collections -> chooseCollection()
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { vm.videos.collect { adapter.submitList(it); b.count.text = "${it.size} видео"; b.empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE } }
            launch { vm.busy.collect { b.progress.visibility = if (it) View.VISIBLE else View.GONE } }
            launch { vm.message.collect { if (it.isNotBlank()) { notice(it); vm.message.value = "" } } }
            launch { vm.collections.collect { } }
        } }
        if (savedInstanceState == null) requestScan()
    }
    private fun requestScan() { permission.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) }
    private fun play(video: Video, list: List<Video>) {
        PlaybackQueue.uris = list.map { it.uri }
        startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra("uri", video.uri))
    }
    private fun createCollection(kind: String) {
        val input = EditText(requireContext()); input.hint = "Название"; input.setSingleLine()
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (kind == "folder") "Новая виртуальная папка" else "Новый плейлист")
            .setView(input).setPositiveButton("Создать", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val name = input.text.toString().trim(); if (name.isEmpty()) { input.error = "Введите название"; return@setOnClickListener }
            lifecycleScope.launch { vm.dao.addCollection(dev.nocturne.player.data.Collection(name = name, kind = kind)) }; dialog.dismiss()
        } }; dialog.show()
    }
    private fun chooseCollection() {
        val list = vm.collections.value
        if (list.isEmpty()) { notice("Создайте плейлист или виртуальную папку через +"); vm.section.value = "all"; return }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Коллекции").setItems(list.map { (if (it.kind == "folder") "Папка · " else "Плейлист · ") + it.name }.toTypedArray()) { _, i ->
            vm.collection.value = list[i].id; vm.section.value = "collection"
        }.setNeutralButton("Удалить…") { _, _ -> MaterialAlertDialogBuilder(requireContext()).setTitle("Удалить коллекцию")
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> lifecycleScope.launch { vm.dao.clearCollection(list[i].id); vm.dao.deleteCollection(list[i].id); vm.section.value = "all" } }.show()
        }.show()
    }
    private fun videoMenu(video: Video) {
        val inCollection = vm.section.value == "collection"
        val actions = mutableListOf("Добавить в коллекцию", "Открыть отдельно")
        if (inCollection) actions.addAll(listOf("Убрать из коллекции", "Переместить в начало"))
        MaterialAlertDialogBuilder(requireContext()).setTitle(video.name).setItems(actions.toTypedArray()) { _, i -> when (i) {
            0 -> { val list = vm.collections.value; if (list.isEmpty()) notice("Сначала создайте коллекцию через +") else MaterialAlertDialogBuilder(requireContext()).setItems(list.map { it.name }.toTypedArray()) { _, index -> lifecycleScope.launch {
                val items = vm.dao.items(list[index].id)
                vm.dao.putItem(CollectionItem(list[index].id, video.uri, (items.maxOfOrNull { it.ordinal } ?: -1) + 1))
            } }.show() }
            1 -> play(video, listOf(video))
            2 -> lifecycleScope.launch { vm.dao.removeItem(vm.collection.value, video.uri) }
            3 -> lifecycleScope.launch { val items = vm.dao.items(vm.collection.value); vm.dao.putItem(CollectionItem(vm.collection.value, video.uri, (items.minOfOrNull { it.ordinal } ?: 0) - 1)) }
        } }.show()
    }
    private fun notice(text: String) { view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() } }
}

object PlaybackQueue { var uris: List<String> = emptyList() }
