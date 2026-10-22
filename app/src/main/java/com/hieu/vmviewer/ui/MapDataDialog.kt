package com.hieu.vmviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hieu.vmviewer.MapDataState
import com.hieu.vmviewer.cam.MapData
import com.hieu.vmviewer.map.Tiles

/**
 * Tải dữ liệu cảnh báo giao thông và nạp vào camera. Hai bước tách nhau vì nối WiFi
 * camera thì máy không có Internet: tải khi đang dùng 4G / WiFi nhà, nạp khi đã nối camera.
 * Chỉ theo dõi mạng Internet khi hộp thoại đang mở.
 */
@Composable
fun MapDataDialog(
    state: MapDataState,
    tiles: Tiles,
    camReady: Boolean,
    onOpen: () -> Unit,
    onCheckLatest: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    DisposableEffect(Unit) {
        tiles.acquire()
        onDispose { tiles.release() }
    }
    LaunchedEffect(camReady) { onOpen() }   // mở hộp thoại / vừa nối được camera -> đọc lại phiên bản
    val network by tiles.network.collectAsState()
    LaunchedEffect(network) { if (network != null) onCheckLatest() }
    val online = network != null
    val busy = state.busy != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Dữ liệu cảnh báo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionRow("Trong camera", when {
                    state.camera?.contains("no", ignoreCase = true) == true -> "chưa có"
                    state.camera != null -> state.camera
                    camReady -> "không đọc được"
                    else -> "chưa nối camera"
                })
                if (camReady && state.cameraNote != null) {
                    Text(
                        "Camera: ${state.cameraNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VersionRow("Đã tải về máy", state.local ?: "chưa có")
                VersionRow("Mới nhất", state.latest ?: if (online) "…" else "cần mạng Internet")
                if (state.busy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(state.busy, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.done?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (!online && !camReady) {
                    Text(
                        "Bật 4G hoặc nối WiFi nhà để tải, rồi nối lại WiFi camera để nạp.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                val newer = MapData.isNewer(state.latest, state.local)
                if (online) {
                    val label = when {
                        state.local == null || newer -> "Tải bản ${state.latest.orEmpty()}".trim()
                        else -> "Tải lại"
                    }
                    if (state.local == null || newer) {
                        Button(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
                if (camReady && state.local != null) {
                    val needed = state.camera == null || MapData.isNewer(state.local, state.camera)
                    val label = if (needed) "Nạp bản ${state.local} vào camera" else "Nạp lại vào camera"
                    if (needed) {
                        Button(onClick = onUpload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = onUpload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Đóng") } },
    )
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
