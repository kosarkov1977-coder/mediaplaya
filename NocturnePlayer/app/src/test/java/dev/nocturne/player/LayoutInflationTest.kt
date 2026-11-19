@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.nocturne.player

import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import dev.nocturne.player.databinding.ActivityPlayerBinding
import dev.nocturne.player.databinding.FragmentLibraryBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LayoutInflationTest {
    @Test fun playerAndLibraryInflateWithMaterialTheme() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Nocturne)
        val inflater = LayoutInflater.from(context)
        val library = FragmentLibraryBinding.inflate(inflater)
        val player = ActivityPlayerBinding.inflate(inflater)
        assertNotNull(library.search)
        assertNotNull(player.player.subtitleView)
        assertNotNull(player.player.videoSurfaceView)
        assertNotNull(player.player.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress))
    }
}
