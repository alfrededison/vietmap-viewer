package com.hieu.vmviewer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    /** Dùng chung cho API + player. Process đã được bind vào WiFi camera (xem CamNetwork). */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Sống cùng process: dùng để trả camera về chế độ ghi kể cả khi màn hình đã đóng. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Thumbnail: tối đa 1 request cùng lúc. Web server trong camera rất yếu,
     * app gốc bắn hàng loạt request cmd=4001 cùng lúc nên cả danh sách lẫn video đều giật.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            http.newBuilder()
                .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
                .build()
        }
        .respectCacheHeaders(false)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbs"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
