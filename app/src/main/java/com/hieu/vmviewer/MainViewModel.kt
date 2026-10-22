package com.hieu.vmviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hieu.vmviewer.cam.CacheBytes
import com.hieu.vmviewer.cam.CamApi
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.CamNetwork
import com.hieu.vmviewer.cam.ClipTime
import com.hieu.vmviewer.cam.ClipTimeCache
import com.hieu.vmviewer.cam.Dl
import com.hieu.vmviewer.cam.DownloadQueue
import com.hieu.vmviewer.cam.FileListCache
import com.hieu.vmviewer.cam.LocalBytes
import com.hieu.vmviewer.cam.LocalVideo
import com.hieu.vmviewer.cam.Mp4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ConnState { WaitingWifi, Connecting, Ready, Error }

data class UiState(
    val conn: ConnState = ConnState.WaitingWifi,
    val files: List<CamFile> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as App
    val http get() = application.http
    val videoCache get() = application.videoCache

    private val net = CamNetwork(app)
    private val api = CamApi(http)
    private val fileCache = FileListCache(app.cacheDir.resolve("files.tsv"))
    private val timeCache = ClipTimeCache(app.cacheDir.resolve("cliptimes2.tsv"))

    val downloads = DownloadQueue(
        context = app,
        client = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build(),
        scope = application.appScope,
        cache = application.videoCache,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Giờ thật từng clip, theo tên file (dùng chung cho file trên camera và bản đã tải). */
    private val _times = MutableStateFlow<Map<String, ClipTime>>(emptyMap())
    val times: StateFlow<Map<String, ClipTime>> = _times

    private val _local = MutableStateFlow<List<LocalVideo>>(emptyList())
    val local: StateFlow<List<LocalVideo>> = _local

    private val timeMutex = Mutex()                      // mỗi lúc 1 request đọc giờ tới camera
    private val timePending = ConcurrentHashMap.newKeySet<String>()
    private val timeFailed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var rangeUnsupported = false

    @Volatile private var foreground = false
    private var connectJob: Job? = null
    private var listJob: Job? = null

    init {
        // Hiện danh sách lần trước ngay, không phải chờ camera đổi chế độ + liệt kê lại
        viewModelScope.launch(Dispatchers.IO) {
            _times.update { timeCache.load() + it }
            val cached = fileCache.load()
            if (cached.isNotEmpty()) {
                downloads.markExisting(downloads.existingNames(), cached)
                _ui.update { if (it.files.isEmpty()) it.copy(files = cached) else it }
            }
        }
        net.start()
        viewModelScope.launch {
            net.wifi.collect { n ->
                if (n == null) {
                    _ui.update { it.copy(conn = ConnState.WaitingWifi) }
                } else if (foreground) {
                    connect()
                }
            }
        }
        // Tải xong khi app đang ở nền -> trả camera về ghi hình
        downloads.onIdle = { if (!foreground) resumeRecording() }
        // Có file tải xong -> cập nhật tab "Đã tải"
        viewModelScope.launch {
            downloads.states.map { m -> m.values.count { it is Dl.Done && it.uri != null } }
                .distinctUntilChanged()
                .collect { refreshLocal() }
        }
    }

    fun refreshLocal() {
        viewModelScope.launch(Dispatchers.IO) { _local.value = downloads.localVideos() }
    }

    fun onForeground() {
        foreground = true
        refreshLocal()
        if (net.wifi.value != null) connect()
    }

    /**
     * An toàn là trên hết: đây là camera hành trình, không được để nó kẹt ở
     * chế độ xem lại. Rời app -> trả về ghi hình (trừ khi đang tải dở).
     */
    fun onBackground() {
        foreground = false
        if (!downloads.isBusy) resumeRecording()
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(conn = ConnState.Connecting, message = null) }
            try {
                if (!api.ping()) throw IllegalStateException("WiFi hiện tại không phải camera Vietmap")
                api.enterPlayback()
                delay(800)
            } catch (e: Exception) {
                _ui.update { it.copy(conn = ConnState.Error, message = e.message ?: e.toString()) }
                return@launch
            }
            // Đã ở chế độ xem lại -> xem được ngay với danh sách cũ, danh sách mới về sau
            _ui.update { it.copy(conn = ConnState.Ready, loading = true) }
            reloadFiles()
        }
    }

    fun refresh() {
        if (_ui.value.conn != ConnState.Ready) { connect(); return }
        reloadFiles()
    }

    /** Mỗi lúc chỉ 1 lệnh 3015 (bấm làm mới khi đang tải -> bỏ qua). */
    private fun reloadFiles() {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch(Dispatchers.IO) {
            try { loadFiles() } catch (e: Exception) {
                _ui.update { it.copy(message = "Không tải được danh sách: ${e.message}") }
            }
        }
    }

    private suspend fun loadFiles() {
        _ui.update { it.copy(loading = true) }
        try {
            var files = api.listFiles()
            if (files.isEmpty()) {          // camera đôi khi cần thêm thời gian sau khi đổi chế độ
                delay(1500)
                files = api.listFiles()
            }
            val sorted = files.sortedByDescending { it.time }
            downloads.markExisting(downloads.existingNames(), sorted)
            _ui.update { it.copy(files = sorted) }
            // Rỗng thường là camera chưa sẵn sàng (status -21), đừng xoá mất cache tốt
            if (sorted.isNotEmpty()) fileCache.save(sorted)
        } finally {
            _ui.update { it.copy(loading = false) }
        }
    }

    /**
     * Gọi trong LaunchedEffect của dòng đang hiện: dòng cuộn khỏi màn hình -> huỷ luôn chỗ xếp hàng.
     * Ưu tiên đọc moov từ cache trình phát; [allowNetwork] thì đọc 128 KB cuối file bằng Range,
     * lần lượt từng file (camera yếu, không bắn song song), chỉ khi app đang mở và camera sẵn sàng.
     */
    suspend fun loadTime(f: CamFile, allowNetwork: Boolean = true) {
        if (f.name in _times.value || f.name in timeFailed || f.size <= 0) return
        if (!timePending.add(f.name)) return
        try {
            withContext(Dispatchers.IO) {
                timeMutex.withLock {
                    val cached = Mp4.findMoov(CacheBytes(videoCache, f.url, f.size))?.let(Mp4::clipTime)
                    if (cached != null) return@withLock putTime(f.name, cached)
                    if (!allowNetwork || !foreground || _ui.value.conn != ConnState.Ready || rangeUnsupported) {
                        return@withLock
                    }
                    val n = minOf(f.size, 128L * 1024)
                    val tail = try { api.readRange(f.url, f.size - n, f.size - 1) } catch (e: IOException) {
                        return@withLock           // lỗi mạng / 404: lần sau hiện lại sẽ thử tiếp
                    }
                    if (tail == null) { rangeUnsupported = true; return@withLock }
                    val t = Mp4.clipTime(tail)
                    if (t != null) putTime(f.name, t) else timeFailed += f.name
                }
            }
        } finally {
            timePending -= f.name
        }
    }

    fun requestLocalTime(v: LocalVideo) {
        if (v.name in _times.value || v.name in timeFailed || !timePending.add(v.name)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val t = Mp4.findMoov(LocalBytes(application.contentResolver, v.uri, v.size))?.let(Mp4::clipTime)
                if (t != null) putTime(v.name, t) else timeFailed += v.name
            } finally {
                timePending -= v.name
            }
        }
    }

    private fun putTime(name: String, t: ClipTime) {
        _times.update { it + (name to t) }
        timeCache.save(_times.value)
    }

    private fun requireReady(): Boolean {
        val ok = _ui.value.conn == ConnState.Ready
        if (!ok) _ui.update { it.copy(message = "Chưa kết nối camera") }
        return ok
    }

    fun download(f: CamFile) {
        if (requireReady()) downloads.enqueue(f)
    }

    fun cancelDownload(f: CamFile) = downloads.cancel(f)

    fun delete(f: CamFile) {
        if (!requireReady()) return
        viewModelScope.launch(Dispatchers.IO) {
            val st = runCatching { api.delete(f) }.getOrElse { -999 }
            if (st == 0) {
                _ui.update { s -> s.copy(files = s.files - f, message = "Đã xoá ${f.name}") }
                fileCache.save(_ui.value.files)
            } else {
                _ui.update { it.copy(message = "Không xoá được (status $st)") }
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun resumeRecording() {
        application.appScope.launch { runCatching { api.resumeRecording() } }
    }

    override fun onCleared() {
        net.stop()
        super.onCleared()
    }
}
