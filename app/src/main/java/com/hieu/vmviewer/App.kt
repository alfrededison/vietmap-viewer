package com.hieu.vmviewer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.hieu.vmviewer.map.Tiles
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

    /**
     * Video đã stream được ghi xuống đĩa: tua lùi / xem lại không phải tải lại
     * từ camera. 1 GB ≈ 7 clip 1 phút. SimpleCache chỉ được có 1 instance / thư mục.
     */
    @get:OptIn(UnstableApi::class)
    val videoCache: SimpleCache by lazy {
        SimpleCache(
            cacheDir.resolve("video"),
            LeastRecentlyUsedCacheEvictor(1L shl 30),
            StandaloneDatabaseProvider(this),
        )
    }

    /** Tile bản đồ: đi mạng có Internet thật, không qua WiFi camera (xem Tiles). */
    val tiles: Tiles by lazy {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        Tiles(this, "VietmapViewer/$version (Android; $packageName)")
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
