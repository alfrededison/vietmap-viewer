package com.hieu.vmviewer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hieu.vmviewer.ui.FileListScreen
import com.hieu.vmviewer.ui.PlayerScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val dls by vm.downloads.states.collectAsStateWithLifecycle()
                var playingPath by rememberSaveable { mutableStateOf<String?>(null) }
                val playing = ui.files.firstOrNull { it.fpath == playingPath }

                if (playing != null) {
                    BackHandler { playingPath = null }
                    PlayerScreen(
                        file = playing,
                        client = vm.http,
                        dl = dls[playing.fpath],
                        onDownload = { vm.download(playing) },
                        onBack = { playingPath = null },
                    )
                } else {
                    FileListScreen(
                        ui = ui,
                        downloads = dls,
                        onPlay = { playingPath = it.fpath },
                        onDownload = vm::download,
                        onCancel = vm::cancelDownload,
                        onDelete = vm::delete,
                        onRefresh = vm::refresh,
                        onRetry = vm::connect,
                        onOpenWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        onExitToRecord = { vm.resumeRecording(); finish() },
                        onMessageShown = vm::clearMessage,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
