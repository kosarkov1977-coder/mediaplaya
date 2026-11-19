package dev.nocturne.player.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.nocturne.player.R
import dev.nocturne.player.data.Video
import dev.nocturne.player.databinding.ItemVideoBinding
import kotlinx.coroutines.*

fun durationText(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
class VideoAdapter(private val scope: CoroutineScope, private val open: (Video) -> Unit, private val menu: (Video) -> Unit) : ListAdapter<Video, VideoAdapter.Holder>(object : DiffUtil.ItemCallback<Video>() {
    override fun areItemsTheSame(a: Video, b: Video) = a.uri == b.uri
    override fun areContentsTheSame(a: Video, b: Video) = a == b
}) {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024 }
    class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) { var job: Job? = null; var signal: CancellationSignal? = null }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(h: Holder, position: Int) {
        val v = getItem(position); val b = h.b
        h.job?.cancel(); h.signal?.cancel(); h.signal = CancellationSignal()
        b.title.text = v.name
        b.meta.text = "${durationText(v.duration)}  ·  ${"%.1f".format(v.size / 1048576.0)} МБ"
        b.watched.progress = if (v.duration > 0) (v.position * 100 / v.duration).toInt() else 0
        b.root.setOnClickListener { open(v) }; b.menu.setOnClickListener { menu(v) }
        b.thumbnail.setImageResource(R.drawable.ic_app)
        val cached = cache.get(v.uri)
        if (cached != null) b.thumbnail.setImageBitmap(cached) else {
            val signal = h.signal!!
            h.job = scope.launch {
                val bitmap = withContext(Dispatchers.IO) { try { b.root.context.contentResolver.loadThumbnail(Uri.parse(v.uri), Size(320, 180), signal) } catch (_: Exception) { null } }
                ensureActive()
                if (bitmap != null) { cache.put(v.uri, bitmap); b.thumbnail.setImageBitmap(bitmap) }
            }
        }
    }
    override fun onViewRecycled(holder: Holder) { holder.job?.cancel(); holder.signal?.cancel() }
}
