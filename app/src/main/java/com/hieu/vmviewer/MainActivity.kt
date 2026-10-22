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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import com.hieu.vmviewer.cam.CacheBytes
import com.hieu.vmviewer.cam.LocalBytes
import com.hieu.vmviewer.cam.nameClock
import com.hieu.vmviewer.cam.nameDate
import com.hieu.vmviewer.ui.DownloadAction
import com.hieu.vmviewer.ui.FileListScreen
import com.hieu.vmviewer.ui.PlayerScreen
import kotlinx.coroutines.delay

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
                val local by vm.local.collectAsStateWithLifecycle()
                val times by vm.times.collectAsStateWithLifecycle()
                val listState = rememberSaveableStateHolder()
                var playingPath by rememberSaveable { mutableStateOf<String?>(null) }
                var playingLocal by rememberSaveable { mutableStateOf<String?>(null) }   // content:// uri
                val playing = ui.files.firstOrNull { it.fpath == playingPath }
                val playingVideo = local.firstOrNull { it.uri.toString() == playingLocal }

                if (playing != null) {
                    BackHandler { playingPath = null }
                    // Không gọi camera lúc đang stream: đợi trình phát đọc moov vào cache rồi lấy từ đó
                    LaunchedEffect(playing.fpath) {
                        repeat(15) {
                            if (playing.name in vm.times.value) return@LaunchedEffect
                            vm.loadTime(playing, allowNetwork = false)
                            delay(2000)
                        }
                    }
                    val t = times[playing.name]
                    PlayerScreen(
                        uri = playing.url,
                        title = if (t != null) "${t.date} · ${t.startClock} – ${t.endClock}"
                        else "${playing.date} · ${playing.startClock?.let { "$it – " }.orEmpty()}${playing.endClock}",
                        bytes = remember(playing.fpath) { CacheBytes(vm.videoCache, playing.url, playing.size) },
                        streaming = true,
                        client = vm.http,
                        cache = vm.videoCache,
                        camReady = ui.conn == ConnState.Ready,
                        actions = { DownloadAction(dls[playing.fpath]) { vm.download(playing) } },
                        onBack = { playingPath = null },
                    )
                } else if (playingVideo != null) {
                    BackHandler { playingLocal = null }
                    LaunchedEffect(playingVideo.uri) { vm.requestLocalTime(playingVideo) }
                    val t = times[playingVideo.name]
                    PlayerScreen(
                        uri = playingVideo.uri.toString(),
                        title = if (t != null) "${t.date} · ${t.startClock} – ${t.endClock}"
                        else "${nameDate(playingVideo.name).orEmpty()} · ${nameClock(playingVideo.name).orEmpty()}",
                        bytes = remember(playingVideo.uri) { LocalBytes(contentResolver, playingVideo.uri, playingVideo.size) },
                        streaming = false,
                        client = vm.http,
                        cache = vm.videoCache,
                        camReady = true,
                        actions = {},
                        onBack = { playingLocal = null },
                    )
                } else {
                    // Giữ state của danh sách (tab, vị trí cuộn) khi mở video rồi back ra
                    listState.SaveableStateProvider("list") {
                        FileListScreen(
                            ui = ui,
                            downloads = dls,
                            local = local,
                            times = times,
                            onPlay = { playingPath = it.fpath },
                            onPlayLocal = { playingLocal = it.uri.toString() },
                            onNeedTime = { vm.loadTime(it) },
                            onNeedLocalTime = vm::requestLocalTime,
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
