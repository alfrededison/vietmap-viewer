package com.hieu.vmviewer.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hieu.vmviewer.ConnState
import com.hieu.vmviewer.UiState
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.Dl

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    ui: UiState,
    downloads: Map<String, Dl>,
    onPlay: (CamFile) -> Unit,
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
        if (m != null && ui.conn == ConnState.Ready) {
            snackbar.showSnackbar(m)
            onMessageShown()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
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
            when (ui.conn) {
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
                ConnState.Ready -> {
                    val normal = ui.files.filter { !it.isEvent }
                    val events = ui.files.filter { it.isEvent }
                    TabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Video (${normal.size})") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Sự kiện (${events.size})") })
                    }
                    if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val list = if (tab == 0) normal else events
                    if (list.isEmpty() && !ui.loading) {
                        StatusPanel(icon = {}, title = "Không có file", body = "Bấm làm mới để tải lại danh sách.")
                    } else {
                        val grouped = list.groupBy { it.date }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
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
                                        dl = downloads[f.fpath],
                                        onPlay = { onPlay(f) },
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

@Composable
private fun FileRow(
    f: CamFile,
    dl: Dl?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
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
                AsyncImage(
                    model = f.thumbUrl,
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
            Text(f.clock, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            is Dl.Done -> IconButton(onClick = {
                dl.uri?.let { uri ->
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            }) { Icon(Icons.Filled.DownloadDone, "Đã tải", tint = MaterialTheme.colorScheme.primary) }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Tải về") }
        }
        if (!f.isEvent && dl !is Dl.Running) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Xoá") }
        }
    }
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
