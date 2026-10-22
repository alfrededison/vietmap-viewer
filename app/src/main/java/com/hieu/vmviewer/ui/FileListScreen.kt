package com.hieu.vmviewer.ui

import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hieu.vmviewer.ConnState
import com.hieu.vmviewer.UiState
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.ClipTime
import com.hieu.vmviewer.cam.Dl
import com.hieu.vmviewer.cam.LocalVideo
import com.hieu.vmviewer.cam.nameClock
import com.hieu.vmviewer.cam.nameDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    ui: UiState,
    downloads: Map<String, Dl>,
    local: List<LocalVideo>,
    times: Map<String, ClipTime>,
    onPlay: (CamFile) -> Unit,
    onPlayLocal: (LocalVideo) -> Unit,
    onNeedTime: suspend (CamFile) -> Unit,
    onNeedLocalTime: (LocalVideo) -> Unit,
    onDownload: (CamFile) -> Unit,
    onCancel: (CamFile) -> Unit,
    onDelete: (CamFile) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenWifi: () -> Unit,
    onExitToRecord: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message, ui.conn) {
        val m = ui.message
        if (m != null && ui.conn != ConnState.Error) {   // Error: lỗi đã hiện trong panel/banner
            snackbar.showSnackbar(m)
            onMessageShown()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scroll = List(3) { rememberLazyListState() }   // mỗi tab nhớ vị trí cuộn riêng
    var toDelete by remember { mutableStateOf<CamFile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VM Viewer") },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Làm mới") }
                    TextButton(onClick = onExitToRecord) {
                        Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE53935), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ghi hình")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Có danh sách (kể cả từ lần trước) thì hiện luôn, trạng thái kết nối chỉ là banner
            val showList = ui.conn == ConnState.Ready || ui.files.isNotEmpty() || local.isNotEmpty()
            if (!showList) when (ui.conn) {
                ConnState.WaitingWifi -> StatusPanel(
                    icon = { Icon(Icons.Filled.Wifi, null, Modifier.size(48.dp)) },
                    title = "Chưa kết nối WiFi camera",
                    body = "Vào cài đặt WiFi, chọn mạng VM_C61Pro… rồi quay lại app.",
                    action = { Button(onClick = onOpenWifi) { Text("Mở cài đặt WiFi") } },
                )
                ConnState.Connecting -> StatusPanel(
                    icon = { CircularProgressIndicator() },
                    title = "Đang kết nối camera…",
                    body = "Chuyển camera sang chế độ xem lại",
                )
                ConnState.Error -> StatusPanel(
                    icon = { Icon(Icons.Filled.ErrorOutline, null, Modifier.size(48.dp)) },
                    title = "Không kết nối được",
                    body = ui.message ?: "",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenWifi) { Text("WiFi") }
                            Button(onClick = onRetry) { Text("Thử lại") }
                        }
                    },
                )
                ConnState.Ready -> Unit
            } else {
                if (ui.conn != ConnState.Ready) ConnBanner(ui, onOpenWifi, onRetry)
                val normal = ui.files.filter { !it.isEvent }
                val events = ui.files.filter { it.isEvent }
                // Tab "Đã tải" gồm cả file đang tải / lỗi (theo thứ tự xếp hàng)
                val active = downloads.filterValues { it !is Dl.Done }.keys
                    .mapNotNull { fp -> ui.files.firstOrNull { it.fpath == fp } }
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Video (${normal.size})") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Sự kiện (${events.size})") })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Đã tải (${local.size + active.size})") })
                }
                if (ui.loading && tab != 2) LinearProgressIndicator(Modifier.fillMaxWidth())
                val list = if (tab == 0) normal else events
                if (tab == 2) {
                    LocalList(scroll[2], local, times, active, onPlayLocal, onNeedLocalTime) { f ->
                        FileRow(
                            f = f,
                            time = times[f.name],
                            camReady = ui.conn == ConnState.Ready,
                            dl = downloads[f.fpath],
                            onNeedTime = { onNeedTime(f) },
                            onPlay = { onPlay(f) },
                            onOpenDownloaded = {},
                            onDownload = { onDownload(f) },
                            onCancel = { onCancel(f) },
                            onDelete = { toDelete = f },
                        )
                    }
                } else if (list.isEmpty() && !ui.loading) {
                    StatusPanel(icon = {}, title = "Không có file", body = "Bấm làm mới để tải lại danh sách.")
                } else {
                    val grouped = list.groupBy { it.date }
                    LazyColumn(state = scroll[tab], contentPadding = PaddingValues(bottom = 24.dp)) {
                        grouped.forEach { (date, files) ->
                            stickyHeader(key = "h_$date") {
                                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(
                                        "$date · ${files.size} file · ${fmtSize(files.sumOf { it.size })}",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            items(files, key = { it.fpath }) { f ->
                                FileRow(
                                    f = f,
                                    time = times[f.name],
                                    camReady = ui.conn == ConnState.Ready,
                                    dl = downloads[f.fpath],
                                    onNeedTime = { onNeedTime(f) },
                                    onPlay = { onPlay(f) },
                                    onOpenDownloaded = { local.firstOrNull { it.name == f.name }?.let(onPlayLocal) },
                                    onDownload = { onDownload(f) },
                                    onCancel = { onCancel(f) },
                                    onDelete = { toDelete = f },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Xoá video trên camera?") },
            text = { Text("${f.name}\n${fmtSize(f.size)} — không thể khôi phục.") },
            confirmButton = { TextButton({ onDelete(f); toDelete = null }) { Text("Xoá") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun ConnBanner(ui: UiState, onOpenWifi: () -> Unit, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.conn == ConnState.Connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                when (ui.conn) {
                    ConnState.WaitingWifi -> "Chưa kết nối WiFi camera"
                    ConnState.Connecting -> "Đang kết nối camera…"
                    else -> "Không kết nối được: ${ui.message.orEmpty()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (ui.conn) {
                ConnState.WaitingWifi -> TextButton(onClick = onOpenWifi) { Text("WiFi") }
                ConnState.Error -> TextButton(onClick = onRetry) { Text("Thử lại") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun StatusPanel(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalList(
    state: LazyListState,
    local: List<LocalVideo>,
    times: Map<String, ClipTime>,
    active: List<CamFile>,
    onPlay: (LocalVideo) -> Unit,
    onNeedTime: (LocalVideo) -> Unit,
    activeRow: @Composable (CamFile) -> Unit,
) {
    if (local.isEmpty() && active.isEmpty()) {
        StatusPanel(icon = {}, title = "Chưa tải video nào", body = "Video tải về nằm trong Movies/VMViewer.")
        return
    }
    val grouped = local.groupBy { times[it.name]?.date ?: nameDate(it.name) ?: "" }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (active.isNotEmpty()) {
            stickyHeader(key = "h_active") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        "Đang tải · ${active.size} file",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(active, key = { "a_" + it.fpath }) { activeRow(it) }
        }
        grouped.forEach { (date, videos) ->
            stickyHeader(key = "lh_$date") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        "$date · ${videos.size} file · ${fmtSize(videos.sumOf { it.size })}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(videos, key = { it.uri.toString() }) { v ->
                LocalRow(v, times[v.name], onPlay = { onPlay(v) }, onNeedTime = { onNeedTime(v) })
            }
        }
    }
}

@Composable
private fun LocalRow(v: LocalVideo, time: ClipTime?, onPlay: () -> Unit, onNeedTime: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(v.uri) { if (time == null) onNeedTime() }
    val thumb by produceState<ImageBitmap?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(v.uri, Size(320, 180), null).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF22252A)),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ClipClock(time, approxStart = nameClock(v.name), approxEnd = null)
            Text(
                "${fmtSize(v.size)} · ${v.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Giờ bắt đầu (to) + "→ kết thúc · độ dài". Chưa có [time] thì hiện giờ gần đúng từ tên file / <TIME>. */
@Composable
private fun ClipClock(time: ClipTime?, approxStart: String?, approxEnd: String?) {
    Text(
        time?.startClock ?: approxStart ?: approxEnd.orEmpty(),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
    val second = when {
        time != null -> "→ ${time.endClock} · ${fmtDuration((time.durationMs / 1000).toInt())}"
        approxStart != null && approxEnd != null -> "→ $approxEnd"
        else -> null
    }
    second?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun FileRow(
    f: CamFile,
    time: ClipTime?,
    camReady: Boolean,
    dl: Dl?,
    onNeedTime: suspend () -> Unit,
    onPlay: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    // Dòng hiện lên -> xin giờ chính xác (lazy, VM xếp hàng từng request)
    LaunchedEffect(f.fpath, camReady) { if (camReady && time == null && f.isVideo) onNeedTime() }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF22252A)),
            contentAlignment = Alignment.Center,
        ) {
            if (f.isVideo) {
                // Chưa kết nối xong -> chỉ lấy thumbnail từ cache, không gọi camera
                AsyncImage(
                    model = remember(f.thumbUrl, camReady) {
                        ImageRequest.Builder(ctx).data(f.thumbUrl)
                            .networkCachePolicy(if (camReady) CachePolicy.ENABLED else CachePolicy.DISABLED)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = 0.85f))
            if (f.isEvent) {
                Icon(
                    Icons.Filled.Lock, null, tint = Color(0xFFFFC107),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ClipClock(time, approxStart = f.startClock, approxEnd = f.endClock)
            Text(
                "${fmtSize(f.size)} · ${f.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            when (dl) {
                is Dl.Queued -> Text("Đang chờ tải…", style = MaterialTheme.typography.bodySmall)
                is Dl.Running -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { dl.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(dl.fraction * 100).toInt()}% · ${fmtSize(dl.bytesPerSec)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is Dl.Failed -> Text("Lỗi: ${dl.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        when (dl) {
            is Dl.Queued, is Dl.Running -> IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Huỷ tải") }
            is Dl.Done -> IconButton(onClick = onOpenDownloaded) {
                Icon(Icons.Filled.DownloadDone, "Xem bản đã tải", tint = MaterialTheme.colorScheme.primary)
            }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Tải về") }
        }
        if (!f.isEvent && dl !is Dl.Running) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Xoá") }
        }
    }
}

fun fmtDuration(sec: Int): String =
    if (sec < 60) "${sec}s" else "%d:%02d".format(sec / 60, sec % 60)

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
