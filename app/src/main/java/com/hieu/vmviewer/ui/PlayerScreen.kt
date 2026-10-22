package com.hieu.vmviewer.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.Dl
import okhttp3.OkHttpClient

/**
 * Phát thẳng qua HTTP. Video ~20 Mbps, WiFi camera ~25 Mbps -> dư rất ít,
 * nên buffer trước vài giây rồi mới chạy, và cho phép buffer xa hơn để không khựng.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    file: CamFile,
    client: OkHttpClient,
    dl: Dl?,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val player = remember(file.url) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 6_000,
            )
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val dataSource = OkHttpDataSource.Factory(client)
        ExoPlayer.Builder(ctx)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(file.url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White)
            }
            Text(
                file.time, color = Color.White, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            when (dl) {
                is Dl.Running -> {
                    CircularProgressIndicator(
                        progress = { dl.fraction }, color = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                is Dl.Queued -> {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                }
                is Dl.Done -> IconButton(onClick = {}) {
                    Icon(Icons.Filled.DownloadDone, "Đã tải", tint = Color(0xFF81C784))
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, "Tải về", tint = Color.White)
                }
            }
        }
    }
}
