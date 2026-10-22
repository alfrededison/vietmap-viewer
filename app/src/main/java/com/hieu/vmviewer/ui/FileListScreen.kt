package com.hieu.vmviewer.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hieu.vmviewer.ConnState
import com.hieu.vmviewer.DeleteProgress
import com.hieu.vmviewer.UiState
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.ClipTime
import com.hieu.vmviewer.cam.Dl
import com.hieu.vmviewer.cam.DownloadQueue
import com.hieu.vmviewer.cam.LocalVideo
import com.hieu.vmviewer.cam.nameClock
import com.hieu.vmviewer.cam.nameDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    onDelete: (List<CamFile>) -> Unit,
    onDeleteLocal: (List<LocalVideo>) -> Unit,
    onCancelDelete: () -> Unit,
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
    var toDelete by remember { mutableStateOf<List<CamFile>?>(null) }
    var toDeleteLocal by remember { mutableStateOf<List<LocalVideo>?>(null) }

    // Lọc theo ngày (dùng chung cho cả 3 tab), null = tất cả
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    fun localDay(v: LocalVideo) = times[v.name]?.date ?: nameDate(v.name) ?: ""
    val allNormal = ui.files.filter { !it.isEvent }
    val allEvents = ui.files.filter { it.isEvent }
    val normal = allNormal.filter { day == null || it.date == day }
    val events = allEvents.filter { day == null || it.date == day }
    val shownLocal = local.filter { day == null || localDay(it) == day }
    // Tab "Đã tải" gồm cả file đang tải / lỗi (theo thứ tự xếp hàng)
    val active = downloads.filterValues { it !is Dl.Done }.keys
        .mapNotNull { fp -> ui.files.firstOrNull { it.fpath == fp } }

    // Chọn nhiều để xoá (giữ lâu 1 dòng): tab Video theo fpath, tab Đã tải theo uri.
    // File sự kiện (khoá) và file đang tải không xoá được nên không chọn được.
    fun canDelete(f: CamFile) = !f.isEvent && downloads[f.fpath].let { it !is Dl.Queued && it !is Dl.Running }
    val selectable: Set<String> = when (tab) {
        0 -> normal.filter(::canDelete).mapTo(HashSet()) { it.fpath }
        2 -> shownLocal.mapTo(HashSet()) { it.uri.toString() }
        else -> emptySet()
    }
    var picked by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val selected = picked intersect selectable      // file đã biến mất (xoá, làm mới) tự bỏ chọn
    val selecting = selected.isNotEmpty()
    // Đọc state lúc bấm (không dùng `selected` của lần compose trước: lambda trong list có thể là bản cũ)
    fun toggle(key: String) { picked = if (key in picked) picked - key else picked + key }
    fun toggleAll(keys: Collection<String>) {
        picked = if (picked.containsAll(keys)) picked - keys.toSet() else picked + keys
    }
    BackHandler(enabled = selecting) { picked = emptySet() }

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Filled.Close, "Bỏ chọn") }
                },
                title = { Text("Đã chọn ${selected.size}") },
                actions = {
                    IconButton(onClick = { toggleAll(selectable) }) { Icon(Icons.Filled.SelectAll, "Chọn tất cả") }
                    IconButton(onClick = {
                        if (tab == 2) toDeleteLocal = shownLocal.filter { it.uri.toString() in selected }
                        else toDelete = normal.filter { it.fpath in selected }
                    }) { Icon(Icons.Filled.Delete, "Xoá") }
                },
            ) else TopAppBar(
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
                ui.deleting?.takeIf { it.total > 1 }?.let { DeleteBanner(it, onCancelDelete) }
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0; picked = emptySet() }, text = { Text("Video (${normal.size})") })
                    Tab(tab == 1, { tab = 1; picked = emptySet() }, text = { Text("Sự kiện (${events.size})") })
                    Tab(tab == 2, { tab = 2; picked = emptySet() }, text = { Text("Đã tải (${shownLocal.size + active.size})") })
                }
                val days = when (tab) {
                    0 -> allNormal.map { it.date }
                    1 -> allEvents.map { it.date }
                    else -> local.map(::localDay)
                }.plus(listOfNotNull(day)).filter { it.isNotEmpty() }.distinct().sortedDescending()
                if (days.size > 1 || day != null) {
                    val scope = rememberCoroutineScope()
                    DayFilter(days, day) {
                        day = it
                        picked = emptySet()
                        scope.launch { scroll[tab].scrollToItem(0) }
                    }
                }
                if (ui.loading && tab != 2) LinearProgressIndicator(Modifier.fillMaxWidth())
                val list = if (tab == 0) normal else events
                if (tab == 2 && day != null && shownLocal.isEmpty() && active.isEmpty()) {
                    StatusPanel(icon = {}, title = "Không có video đã tải ngày ${dayLabel(day!!)}", body = "")
                } else if (tab == 2) {
                    LocalList(
                        scroll[2], shownLocal, times, active, selected,
                        onPlay = onPlayLocal, onNeedTime = onNeedLocalTime,
                        onToggle = ::toggle, onToggleAll = ::toggleAll,
                    ) { f ->
                        FileRow(
                            f = f,
                            time = times[f.name],
                            camReady = ui.conn == ConnState.Ready,
                            dl = downloads[f.fpath],
                            selecting = selecting,
                            selected = false,
                            onSelect = null,
                            onNeedTime = { onNeedTime(f) },
                            onPlay = { onPlay(f) },
                            onOpenDownloaded = {},
                            onDownload = { onDownload(f) },
                            onCancel = { onCancel(f) },
                            onDelete = if (canDelete(f)) { { toDelete = listOf(f) } } else null,
                        )
                    }
                } else if (list.isEmpty() && !ui.loading) {
                    if (day != null) StatusPanel(icon = {}, title = "Không có file ngày ${dayLabel(day!!)}", body = "")
                    else StatusPanel(icon = {}, title = "Không có file", body = "Bấm làm mới để tải lại danh sách.")
                } else {
                    val grouped = list.groupBy { it.date }
                    LazyColumn(state = scroll[tab], contentPadding = PaddingValues(bottom = 24.dp)) {
                        grouped.forEach { (date, files) ->
                            stickyHeader(key = "h_$date") {
                                val keys = files.filter { it.fpath in selectable }.map { it.fpath }
                                DateHeader(
                                    "$date · ${files.size} file · ${fmtSize(files.sumOf { it.size })}",
                                    check = checkState(selected, keys),
                                    onCheck = { toggleAll(keys) },
                                )
                            }
                            items(files, key = { it.fpath }) { f ->
                                FileRow(
                                    f = f,
                                    time = times[f.name],
                                    camReady = ui.conn == ConnState.Ready,
                                    dl = downloads[f.fpath],
                                    selecting = selecting,
                                    selected = f.fpath in selected,
                                    onSelect = if (f.fpath in selectable) { { toggle(f.fpath) } } else null,
                                    onNeedTime = { onNeedTime(f) },
                                    onPlay = { onPlay(f) },
                                    onOpenDownloaded = { local.firstOrNull { it.name == f.name }?.let(onPlayLocal) },
                                    onDownload = { onDownload(f) },
                                    onCancel = { onCancel(f) },
                                    onDelete = if (canDelete(f)) { { toDelete = listOf(f) } } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { fs ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (fs.size == 1) "Xoá video trên camera?" else "Xoá ${fs.size} video trên camera?") },
            text = {
                val what = if (fs.size == 1) fs[0].name else "${fs.size} file"
                Text("$what\n${fmtSize(fs.sumOf { it.size })} — không thể khôi phục.")
            },
            confirmButton = { TextButton({ onDelete(fs); toDelete = null; picked = emptySet() }) { Text("Xoá") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Huỷ") } },
        )
    }
    toDeleteLocal?.let { vs ->
        AlertDialog(
            onDismissRequest = { toDeleteLocal = null },
            title = { Text("Xoá ${vs.size} video đã tải?") },
            text = {
                Text("${fmtSize(vs.sumOf { it.size })} trong Movies/${DownloadQueue.FOLDER} trên điện thoại. Bản trên camera (nếu còn) không bị ảnh hưởng.")
            },
            confirmButton = { TextButton({ onDeleteLocal(vs); toDeleteLocal = null; picked = emptySet() }) { Text("Xoá") } },
            dismissButton = { TextButton({ toDeleteLocal = null }) { Text("Huỷ") } },
        )
    }
}

private val DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/** "2026/10/20" -> "T3 20/10" (năm khác năm nay thì thêm năm). */
private fun dayLabel(day: String): String {
    val d = runCatching { LocalDate.parse(day, DAY) }.getOrNull() ?: return day
    val wd = if (d.dayOfWeek == DayOfWeek.SUNDAY) "CN" else "T${d.dayOfWeek.value + 1}"
    val dm = "%02d/%02d".format(d.dayOfMonth, d.monthValue)
    return if (d.year == LocalDate.now().year) "$wd $dm" else "$wd $dm/${d.year}"
}

/** Dải chip chọn ngày; bấm lại ngày đang chọn = bỏ lọc. */
@Composable
private fun DayFilter(days: List<String>, day: String?, onPick: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilterChip(selected = day == null, onClick = { onPick(null) }, label = { Text("Tất cả ngày") }) }
        items(days) { d ->
            FilterChip(selected = d == day, onClick = { onPick(if (d == day) null else d) }, label = { Text(dayLabel(d)) })
        }
    }
}

/** null = không ở chế độ chọn (hoặc nhóm không có gì chọn được). */
private fun checkState(selected: Set<String>, keys: Collection<String>): ToggleableState? = when {
    selected.isEmpty() || keys.isEmpty() -> null
    selected.containsAll(keys) -> ToggleableState.On
    keys.any { it in selected } -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}

/** Tiêu đề nhóm theo ngày; đang chọn nhiều thì bấm để chọn / bỏ chọn cả ngày. */
@Composable
private fun DateHeader(text: String, check: ToggleableState? = null, onCheck: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (check != null) Modifier.clickable(onClick = onCheck) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (check != null) {
                Icon(
                    when (check) {
                        ToggleableState.On -> Icons.Filled.CheckBox
                        ToggleableState.Indeterminate -> Icons.Filled.IndeterminateCheckBox
                        ToggleableState.Off -> Icons.Filled.CheckBoxOutlineBlank
                    },
                    "Chọn cả ngày", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DeleteBanner(p: DeleteProgress, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (p.stopping) "Đang dừng xoá…" else "Đang xoá ${p.done + 1}/${p.total} file trên camera…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, enabled = !p.stopping) { Text("Huỷ") }
            }
            LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        }
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
    selected: Set<String>,
    onPlay: (LocalVideo) -> Unit,
    onNeedTime: (LocalVideo) -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: (List<String>) -> Unit,
    activeRow: @Composable (CamFile) -> Unit,
) {
    if (local.isEmpty() && active.isEmpty()) {
        StatusPanel(icon = {}, title = "Chưa tải video nào", body = "Video tải về nằm trong Movies/VMViewer.")
        return
    }
    val grouped = local.groupBy { times[it.name]?.date ?: nameDate(it.name) ?: "" }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (active.isNotEmpty()) {
            stickyHeader(key = "h_active") { DateHeader("Đang tải · ${active.size} file") }
            items(active, key = { "a_" + it.fpath }) { activeRow(it) }
        }
        grouped.forEach { (date, videos) ->
            stickyHeader(key = "lh_$date") {
                val keys = videos.map { it.uri.toString() }
                DateHeader(
                    "$date · ${videos.size} file · ${fmtSize(videos.sumOf { it.size })}",
                    check = checkState(selected, keys),
                    onCheck = { onToggleAll(keys) },
                )
            }
            items(videos, key = { it.uri.toString() }) { v ->
                val key = v.uri.toString()
                LocalRow(
                    v, times[v.name],
                    selecting = selected.isNotEmpty(),
                    selected = key in selected,
                    onSelect = { onToggle(key) },
                    onPlay = { onPlay(v) },
                    onNeedTime = { onNeedTime(v) },
                )
            }
        }
    }
}

@Composable
private fun LocalRow(
    v: LocalVideo,
    time: ClipTime?,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onNeedTime: () -> Unit,
) {
    val ctx = LocalContext.current
    LaunchedEffect(v.uri) { if (time == null) onNeedTime() }
    val thumb by produceState<ImageBitmap?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(v.uri, Size(320, 180), null).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.selectableRow(selecting, selected, onSelect, onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
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
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() })
    }
}

/**
 * Bấm = phát (đang chọn nhiều thì = chọn / bỏ chọn), giữ lâu = bắt đầu chọn.
 * [onSelect] null = dòng không chọn được (mờ đi khi đang chọn).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.selectableRow(
    selecting: Boolean,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    onPlay: () -> Unit,
): Modifier = fillMaxWidth()
    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    .combinedClickable(
        onClick = { if (selecting) onSelect?.invoke() else onPlay() },
        onLongClick = onSelect,
    )
    .alpha(if (selecting && onSelect == null) 0.4f else 1f)

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
    selecting: Boolean,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    onNeedTime: suspend () -> Unit,
    onPlay: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    // Dòng hiện lên -> xin giờ chính xác (lazy, VM xếp hàng từng request)
    LaunchedEffect(f.fpath, camReady) { if (camReady && time == null && f.isVideo) onNeedTime() }
    Row(
        Modifier.selectableRow(selecting, selected, onSelect, onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
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
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onSelect?.invoke() }, enabled = onSelect != null)
            return@Row
        }
        when (dl) {
            is Dl.Queued, is Dl.Running -> IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Huỷ tải") }
            is Dl.Done -> IconButton(onClick = onOpenDownloaded) {
                Icon(Icons.Filled.DownloadDone, "Xem bản đã tải", tint = MaterialTheme.colorScheme.primary)
            }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Tải về") }
        }
        onDelete?.let { IconButton(onClick = it) { Icon(Icons.Filled.Delete, "Xoá") } }
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
