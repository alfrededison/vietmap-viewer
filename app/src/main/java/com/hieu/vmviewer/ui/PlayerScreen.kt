package com.hieu.vmviewer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hieu.vmviewer.cam.ByteSource
import com.hieu.vmviewer.cam.Dl
import com.hieu.vmviewer.cam.GpsPoint
import com.hieu.vmviewer.cam.Mp4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phát thẳng qua HTTP. Video ~20 Mbps, WiFi camera ~25 Mbps -> dư rất ít,
 * nên buffer trước vài giây rồi mới chạy, và cho phép buffer xa hơn để không khựng.
 * Phần đã tải được ghi vào [cache] nên tua lùi không phải tải lại.
 *
 * [streaming] = false: video đã tải (content://), phát thẳng từ máy, không cần camera.
 * [bytes] dùng để đọc GPS trong file (cache trình phát / file máy, không gọi camera).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    uri: String,
    title: String,
    bytes: ByteSource,
    streaming: Boolean,
    client: OkHttpClient,
    cache: Cache,
    camReady: Boolean,
    actions: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    // Giữ vị trí đang xem qua tắt màn hình / Android kill process
    var resumeAt by rememberSaveable(uri) { mutableLongStateOf(0L) }
    val player = remember(uri) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 6_000,
            )
            .setBackBuffer(10_000, /* retainBackBufferFromKeyframe = */ true)  // nút lùi 5s tức thì
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val dataSource = if (streaming) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            DefaultDataSource.Factory(ctx)
        }
        ExoPlayer.Builder(ctx)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri), resumeAt)
                playWhenReady = true
            }
    }
    var error by remember(player) { mutableStateOf<PlaybackException?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerErrorChanged(e: PlaybackException?) { error = e }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Chỉ stream khi camera đã ở chế độ xem lại. Rớt kết nối rồi có lại -> phát tiếp từ chỗ cũ.
    LaunchedEffect(player, camReady) {
        if (camReady && player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    // Ra nền (tắt màn hình...) -> dừng hẳn cả phát lẫn tải, nhớ vị trí; camera sẽ bị trả về
    // chế độ ghi nên không được chen request vào. stop() -> IDLE, kết nối lại thì prepare() tiếp.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                resumeAt = player.currentPosition
                player.pause()
                if (streaming) player.stop()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Xoay ngang: theo cảm biến, hoặc ép bằng nút toàn màn hình (kể cả khi tắt tự xoay)
    val activity = ctx as? Activity
    var orientation by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(orientation) {
        activity?.requestedOrientation = orientation
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(landscape) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = landscape) { orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

    // GPS: mỗi giây đọc các block đã có (trong cache khi stream, cả file khi đã tải).
    // Block đọc được mà không giải mã được (chưa bắt GPS…) thì bỏ qua luôn, không thử lại.
    val gps = remember(uri) { mutableStateMapOf<Int, GpsPoint>() }
    LaunchedEffect(player) {
        var index: List<Pair<Long, Int>>? = null
        val bad = mutableSetOf<Int>()
        while (index == null || gps.size + bad.size < index.size) {
            withContext(Dispatchers.IO) {
                if (index == null) index = Mp4.findMoov(bytes)?.let(Mp4::gpsIndex)
                    ?: if (streaming) null else emptyList()      // file máy: không có moov thì thôi
                index?.forEachIndexed { i, (off, _) ->
                    if (i in gps || i in bad) return@forEachIndexed
                    val block = bytes.read(off, 256) ?: return@forEachIndexed   // chưa vào cache
                    Mp4.gpsPoint(block)?.let { gps[i] = it } ?: bad.add(i)
                }
            }
            delay(1000)
        }
    }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(200)
        }
    }
    // Chữ trên hình nhảy giây ở t = 0.5, 1.5, … (giây k phủ [k−0.5, k+0.5)); bản ghi i = giây i+1.
    // Bản ghi chưa đọc tới -> lấy bản gần nhất trước đó, giờ thì cộng bù cho đúng.
    val point by remember(uri) {
        derivedStateOf {
            val i = ((positionMs + 500) / 1000 - 1).toInt()
            val j = (i.coerceAtLeast(0) downTo 0).firstOrNull { it in gps } ?: gps.keys.minOrNull()
            j?.let { gps.getValue(it).let { p -> p.copy(time = p.time.plusSeconds((i - it).toLong())) } }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        keepScreenOn = true
                        setShowRewindButton(true)
                        setShowFastForwardButton(true)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                            controlsVisible = v == View.VISIBLE
                        })
                        setFullscreenButtonClickListener { full ->
                            orientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                },
                update = { it.setFullscreenButtonState(landscape) },
                modifier = Modifier.fillMaxSize(),
            )

            val err = error
            when {
                !camReady -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Đang chờ kết nối camera…", color = Color.White)
                }
                err != null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Lỗi phát video: ${err.errorCodeName}", color = Color.White)
                    Button(onClick = { player.prepare() }) { Text("Thử lại") }
                }
            }

            if (controlsVisible || !camReady || err != null) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f))
                        .statusBarsPadding().displayCutoutPadding().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White)
                    }
                    Text(
                        title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    actions()
                }
            }
        }
        point?.let { if (!landscape) GpsPanel(it) }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Dưới video khi xem dọc. Không có bản đồ trong app vì WiFi camera không có internet. */
@Composable
private fun GpsPanel(p: GpsPoint) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF15171A)).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${p.speedKmh}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Text(" km/h", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(p.time.format(CLOCK), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon),
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
            )
            // Camera in lên hình tốc độ của giây trước (có số lẻ); file chỉ lưu km/h nguyên của giây này
            Text(
                "GPS 1 lần/giây · tốc độ in trên hình trễ 1 s",
                color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall,
            )
        }
        // Mở app bản đồ: app đó dùng mạng riêng (4G), không bị bind vào WiFi camera như app này
        IconButton(onClick = {
            val geo = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, geo)) } catch (_: ActivityNotFoundException) {}
        }) { Icon(Icons.Filled.Map, "Mở bản đồ", tint = Color.White) }
    }
}

/** Nút tải / tiến độ tải trên thanh trên cùng của trình phát. */
@Composable
fun DownloadAction(dl: Dl?, onDownload: () -> Unit) {
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
