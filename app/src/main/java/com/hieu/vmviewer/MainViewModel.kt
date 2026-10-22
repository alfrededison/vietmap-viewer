package com.hieu.vmviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hieu.vmviewer.cam.CamApi
import com.hieu.vmviewer.cam.CamFile
import com.hieu.vmviewer.cam.CamNetwork
import com.hieu.vmviewer.cam.DownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val net = CamNetwork(app)
    private val api = CamApi(http)

    val downloads = DownloadQueue(
        context = app,
        client = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build(),
        scope = application.appScope,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var foreground = false
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        net.start()
        viewModelScope.launch {
            net.wifi.collect { n ->
                if (n == null) {
                    heartbeatJob?.cancel()
                    _ui.update { it.copy(conn = ConnState.WaitingWifi) }
                } else if (foreground) {
                    connect()
                }
            }
        }
        // Tải xong khi app đang ở nền -> trả camera về ghi hình
        downloads.onIdle = { if (!foreground) resumeRecording() }
    }

    fun onForeground() {
        foreground = true
        if (net.wifi.value != null) connect()
    }

    /**
     * An toàn là trên hết: đây là camera hành trình, không được để nó kẹt ở
     * chế độ xem lại. Rời app -> trả về ghi hình (trừ khi đang tải dở).
     */
    fun onBackground() {
        foreground = false
        heartbeatJob?.cancel()
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
                loadFiles()
                _ui.update { it.copy(conn = ConnState.Ready) }
                startHeartbeat()
            } catch (e: Exception) {
                _ui.update { it.copy(conn = ConnState.Error, message = e.message ?: e.toString()) }
            }
        }
    }

    fun refresh() {
        if (_ui.value.conn != ConnState.Ready) { connect(); return }
        viewModelScope.launch(Dispatchers.IO) {
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
        } finally {
            _ui.update { it.copy(loading = false) }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                api.heartbeat()
            }
        }
    }

    fun download(f: CamFile) = downloads.enqueue(f)
    fun cancelDownload(f: CamFile) = downloads.cancel(f)

    fun delete(f: CamFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = runCatching { api.delete(f) }.getOrElse { -999 }
            if (st == 0) {
                _ui.update { s -> s.copy(files = s.files - f, message = "Đã xoá ${f.name}") }
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
