package com.hieu.vmviewer

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
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
import com.hieu.vmviewer.cam.MapData
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
    val deleting: DeleteProgress? = null,
)

/** Xoá hàng loạt trên camera: [done] file đã xử lý / [total]; [stopping] = đã bấm huỷ, chờ lệnh đang gửi xong. */
data class DeleteProgress(val done: Int, val total: Int, val stopping: Boolean = false)

/** Hộp thoại dữ liệu cảnh báo; phiên bản null = chưa biết. [busy] = việc đang làm, null = rảnh. */
data class MapDataState(
    val camera: String? = null,
    val cameraNote: String? = null,      // vì sao chưa đọc được [camera]
    val local: String? = null,
    val latest: String? = null,
    val busy: String? = null,
    val error: String? = null,
    val done: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as App
    val http get() = application.http
    val videoCache get() = application.videoCache
    val tiles get() = application.tiles

    private val net = CamNetwork(app)
    private val api = CamApi(http)
    private val fileCache = FileListCache(app.cacheDir.resolve("files.tsv"))
    private val timeCache = ClipTimeCache(app.cacheDir.resolve("cliptimes2.tsv"))
    private val mapData = MapData(app.filesDir.resolve("mapdata"))

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

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bản đồ dưới video đã tải; tắt = không theo dõi mạng, không tải tile nào. */
    private val _mapEnabled = MutableStateFlow(prefs.getBoolean(PREF_MAP, true))
    val mapEnabled: StateFlow<Boolean> = _mapEnabled

    fun setMapEnabled(on: Boolean) {
        _mapEnabled.value = on
        prefs.edit().putBoolean(PREF_MAP, on).apply()
    }

    private val timeMutex = Mutex()                      // mỗi lúc 1 request đọc giờ tới camera
    private val timePending = ConcurrentHashMap.newKeySet<String>()
    private val timeFailed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var rangeUnsupported = false

    @Volatile private var foreground = false
    private var connectJob: Job? = null
    private var listJob: Job? = null
    private var deleteJob: Job? = null
    @Volatile private var deleteCancelled = false
    private var mapJob: Job? = null       // tải / nạp, mỗi lúc một việc
    private var verJob: Job? = null       // đọc phiên bản trong camera
    private var latestJob: Job? = null    // hỏi bản mới nhất trên server

    private val _mapState = MutableStateFlow(MapDataState())
    val mapState: StateFlow<MapDataState> = _mapState

    /** Đang nạp dữ liệu cảnh báo: camera ở chế độ quay phim, không gửi lệnh nào khác. */
    @Volatile private var mapUploading = false

    /** Video đã tải không thuộc app (cài lại app…): Activity mở hộp thoại hệ thống xin xoá. */
    private val _deleteConsent = MutableStateFlow<IntentSender?>(null)
    val deleteConsent: StateFlow<IntentSender?> = _deleteConsent
    private var consentFor: List<LocalVideo> = emptyList()

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
        // Đang nạp dữ liệu cảnh báo: không chen lệnh, nạp xong tự trả về ghi hình
        if (!downloads.isBusy && !mapUploading) resumeRecording()
    }

    fun connect() {
        if (connectJob?.isActive == true || mapUploading) return
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(conn = ConnState.Connecting, message = null) }
            try {
                if (!api.ping()) throw IllegalStateException("WiFi hiện tại không phải camera Vietmap")
                api.enterPlayback()
                // Rời app đúng lúc lệnh 3001 par=2 đang đi: resumeRecording của onBackground có thể tới trước
                if (!foreground) { api.resumeRecording(); return@launch }
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

    /**
     * Mỗi lúc chỉ 1 lệnh 3015 (bấm làm mới khi đang tải -> bỏ qua). Đang xoá cũng bỏ qua:
     * không chen request vào camera, và danh sách lấy giữa chừng sẽ làm file vừa xoá hiện lại.
     */
    private fun reloadFiles() {
        if (listJob?.isActive == true) return
        if (mapUploading) {
            _ui.update { it.copy(loading = false) }
            return
        }
        if (deleteJob?.isActive == true) {
            _ui.update { it.copy(loading = false, message = "Đang xoá, làm mới sau nhé") }
            return
        }
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
                    if (!allowNetwork || !foreground || _ui.value.conn != ConnState.Ready || rangeUnsupported || mapUploading) {
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
        else if (mapUploading) _ui.update { it.copy(message = "Đang nạp dữ liệu cảnh báo, đợi xong đã") }
        return ok && !mapUploading
    }

    fun download(f: CamFile) {
        if (requireReady()) downloads.enqueue(f)
    }

    fun cancelDownload(f: CamFile) = downloads.cancel(f)

    /**
     * Xoá lần lượt từng file (camera yếu, không bắn song song); xoá xong file nào thì
     * file đó biến khỏi danh sách ngay. Ra nền / mất kết nối / [cancelDelete] -> dừng, không gửi
     * lệnh tiếp (lệnh đang gửi dở vẫn chạy xong: không ngắt giữa chừng một request tới camera).
     */
    fun delete(files: List<CamFile>) {
        if (files.isEmpty() || !requireReady()) return
        if (deleteJob?.isActive == true) {
            _ui.update { it.copy(message = "Đang xoá, đợi xong đã") }
            return
        }
        deleteCancelled = false
        _ui.update { it.copy(deleting = DeleteProgress(0, files.size)) }
        deleteJob = viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var err: String? = null
            try {
                listJob?.join()      // đợi 3015 đang chạy xong, không thì danh sách cũ ghi đè lên
                for ((i, f) in files.withIndex()) {
                    if (deleteCancelled) break
                    if (!foreground || _ui.value.conn != ConnState.Ready) { err = "mất kết nối camera"; break }
                    _ui.update { s -> s.copy(deleting = s.deleting?.copy(done = i)) }
                    val st = try { api.delete(f) } catch (e: Exception) { err = e.message ?: e.toString(); break }
                    if (st != 0) { err = "status $st"; continue }      // file này lỗi, các file khác vẫn xoá
                    ok++
                    _ui.update { s -> s.copy(files = s.files - f) }
                }
            } finally {
                _ui.update { it.copy(deleting = null) }
            }
            if (ok > 0) fileCache.save(_ui.value.files)
            val msg = when {
                deleteCancelled -> "Đã huỷ, xoá được $ok/${files.size} file" + err?.let { ", lỗi: $it" }.orEmpty()
                err == null && files.size == 1 -> "Đã xoá ${files[0].name}"
                err == null -> "Đã xoá $ok file"
                ok == 0 -> "Không xoá được ($err)"
                else -> "Đã xoá $ok/${files.size} file, lỗi: $err"
            }
            _ui.update { it.copy(message = msg) }
        }
    }

    fun cancelDelete() {
        if (deleteJob?.isActive != true) return
        deleteCancelled = true
        _ui.update { s -> s.copy(deleting = s.deleting?.copy(stopping = true)) }
    }

    fun deleteLocal(videos: List<LocalVideo>) {
        if (videos.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val denied = downloads.deleteLocal(videos)
            localDeleted(videos - denied.toSet())
            if (denied.isEmpty()) return@launch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                consentFor = denied
                _deleteConsent.value = MediaStore.createDeleteRequest(
                    application.contentResolver, denied.map { it.uri },
                ).intentSender
            } else {
                _ui.update { it.copy(message = "Không xoá được ${denied.size} file (không do app này tạo)") }
            }
        }
    }

    fun consentLaunched() { _deleteConsent.value = null }

    fun onDeleteConsent(granted: Boolean) {
        val vs = consentFor
        consentFor = emptyList()
        if (granted) localDeleted(vs)
    }

    private fun localDeleted(videos: List<LocalVideo>) {
        if (videos.isEmpty()) return
        downloads.forget(videos.mapTo(HashSet()) { it.name })
        _local.update { it - videos.toSet() }
        _ui.update { it.copy(message = "Đã xoá ${videos.size} file khỏi máy") }
        refreshLocal()
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Mở hộp thoại / vừa nối camera: đọc bản đã tải, và bản trong camera nếu đang kết nối. */
    fun openMapData() {
        _mapState.update { it.copy(local = mapData.localVersion(), error = null, done = null) }
        if (verJob?.isActive == true || mapUploading) return
        if (_ui.value.conn != ConnState.Ready) return
        val skip = when {
            downloads.isBusy -> "đang tải video, mở lại sau"
            deleteJob?.isActive == true -> "đang xoá, mở lại sau"
            else -> null
        }
        if (skip != null) { _mapState.update { it.copy(cameraNote = skip) }; return }
        verJob = viewModelScope.launch(Dispatchers.IO) {
            listJob?.join()
            readCameraVersion()
        }
    }

    /** Đọc bản trong camera; lỗi thì giữ bản đã biết và ghi lý do vào [MapDataState.cameraNote]. */
    private fun readCameraVersion() {
        try {
            val v = api.mapDataVersion()
            _mapState.update { it.copy(camera = v, cameraNote = null) }
        } catch (e: Exception) {
            _mapState.update { it.copy(cameraNote = e.message ?: e.toString()) }
        }
    }

    /** Có mạng Internet (hộp thoại theo dõi [tiles].network) -> hỏi bản mới nhất; chưa có thì dòng "Mới nhất" hiện "…". */
    fun checkMapDataLatest() {
        if (latestJob?.isActive == true || tiles.network.value == null) return
        latestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val rel = mapData.fetchLatest(tiles.http)
                _mapState.update { it.copy(latest = rel.ver) }
            } catch (e: Exception) {
                _mapState.update { it.copy(error = "Không kiểm tra được bản mới: ${e.message ?: e}") }
            }
        }
    }

    fun downloadMapData() {
        if (mapJob?.isActive == true) return
        if (tiles.network.value == null) {
            _mapState.update { it.copy(error = "Cần mạng có Internet (4G hoặc WiFi nhà) để tải") }
            return
        }
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            _mapState.update { it.copy(busy = "Đang tải dữ liệu cảnh báo…", error = null, done = null) }
            try {
                val rel = mapData.fetchLatest(tiles.http)
                mapData.download(tiles.http, rel)
                _mapState.update {
                    it.copy(latest = rel.ver, local = rel.ver, done = "Đã tải bản ${rel.ver}. Nối WiFi camera rồi bấm Nạp vào camera.")
                }
            } catch (e: Exception) {
                _mapState.update { it.copy(error = "Tải không được: ${e.message ?: e}") }
            } finally {
                _mapState.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * Nạp file đã tải vào camera, ngay trong chế độ xem lại (camera đang không ghi, như app gốc cần).
     * Trong lúc nạp, onBackground / connect không gửi lệnh nào: nạp xong mới trả về ghi hình nếu
     * app đã ra nền (bất biến an toàn), hoặc kết nối lại nếu WiFi đã rớt giữa chừng.
     * Chạy trong appScope như DownloadQueue: đóng app giữa chừng vẫn trả camera về ghi hình.
     */
    fun uploadMapData() {
        if (mapJob?.isActive == true) return
        val file = mapData.localFile()
        val err = when {
            file == null -> "Chưa tải dữ liệu về máy"
            _ui.value.conn != ConnState.Ready -> "Chưa kết nối camera"
            downloads.isBusy -> "Đang tải video, đợi xong đã"
            deleteJob?.isActive == true -> "Đang xoá, đợi xong đã"
            else -> null
        }
        if (err != null) { _mapState.update { it.copy(error = err) }; return }
        mapUploading = true
        _mapState.update { it.copy(busy = "Đang nạp vào camera… đừng tắt camera hay rời app", error = null, done = null) }
        mapJob = application.appScope.launch {
            try {
                listJob?.join()
                verJob?.join()
                // Giữ khoá đọc giờ clip: request Range đang chạy dở xong mới bắt đầu
                timeMutex.withLock { api.uploadMapData(file!!) }
                _mapState.update { it.copy(done = "Đã nạp bản ${mapData.localVersion()} vào camera") }
            } catch (e: Exception) {
                _mapState.update { it.copy(error = "Nạp không được: ${e.message ?: e}") }
            } finally {
                mapUploading = false
                _mapState.update { it.copy(busy = null) }
            }
            // Ra nền trong lúc nạp: onBackground đã nhường, giờ mới trả về ghi hình
            if (!foreground) { runCatching { api.resumeRecording() }; return@launch }
            // WiFi rớt trong lúc nạp (camera khởi động lại?) thì connect() đã bị bỏ qua: kết nối lại
            if (_ui.value.conn != ConnState.Ready) {
                if (net.wifi.value != null) connect()
                return@launch
            }
            readCameraVersion()
        }
    }

    fun resumeRecording() {
        application.appScope.launch { runCatching { api.resumeRecording() } }
    }

    override fun onCleared() {
        net.stop()
        super.onCleared()
    }

    private companion object {
        const val PREF_MAP = "map"
    }
}
