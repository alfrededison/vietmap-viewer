package com.hieu.vmviewer.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.MainThread
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Tile OpenStreetMap — thứ duy nhất trong app đi ra Internet.
 *
 * Chỉ dùng cho video đã tải: nối WiFi camera thì máy không ra được Internet, kể cả qua 4G.
 * Người dùng chuyển sang mạng có Internet rồi xem; nhưng process có thể vẫn đang bind vào
 * WiFi camera, nên tile đi bằng client riêng gắn thẳng vào một mạng đã kiểm tra có Internet.
 * Không có mạng như vậy -> chỉ đọc tile trong cache đĩa, không bao giờ gửi qua WiFi camera.
 */
class Tiles(private val context: Context, private val userAgent: String) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network

    private val lock = Any()
    /** Các mạng đã kiểm tra có Internet; mạng không phải 4G được ưu tiên (đỡ tốn data). */
    private val validated = LinkedHashMap<Network, Boolean>()   // network -> là 4G
    private var users = 0                                        // giữ [lock]

    // Callback chạy trên thread riêng của ConnectivityManager; sau release() thì bỏ qua
    // (một callback đang chạy dở có thể tới sau khi đã gỡ đăng ký).
    private val listener = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(lock) {
                if (users == 0) return
                validated[network] = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                pickLocked()
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (users == 0) return
                validated.remove(network)
                pickLocked()
            }
        }
    }

    private fun pickLocked() {
        val n = validated.entries.lastOrNull { !it.value }?.key ?: validated.keys.lastOrNull()
        if (n == _network.value) return
        _network.value = n
        // Kết nối cũ gắn với mạng cũ. Đóng socket TLS là gửi dữ liệu (close_notify) -> không làm
        // trên main thread (release() chạy ở đó), nếu không sẽ NetworkOnMainThreadException.
        if (clientMade) thread(name = "tiles-evict") { client.connectionPool.evictAll() }
    }

    /** Gọi khi bản đồ hiện ra; mỗi [acquire] phải có một [release]. */
    @MainThread
    fun acquire() {
        synchronized(lock) {
            if (users++ > 0) return
            validated.clear()
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build(),
            listener,
        )
    }

    @MainThread
    fun release() {
        synchronized(lock) {
            if (--users > 0) return
            validated.clear()
            pickLocked()
        }
        runCatching { cm.unregisterNetworkCallback(listener) }
    }

    // Socket / DNS đi theo mạng đang chọn lúc mở kết nối; không có mạng thì lỗi luôn
    private fun current(): Network = _network.value ?: throw IOException("Không có mạng Internet")

    private val sockets = object : SocketFactory() {
        override fun createSocket(): Socket = current().socketFactory.createSocket()
        override fun createSocket(host: String?, port: Int): Socket =
            current().socketFactory.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
            current().socketFactory.createSocket(host, port, local, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            current().socketFactory.createSocket(host, port)
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
            current().socketFactory.createSocket(host, port, local, localPort)
    }

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            _network.value?.getAllByName(hostname)?.toList()
                ?: throw UnknownHostException("Không có mạng Internet cho $hostname")
    }

    @Volatile private var clientMade = false
    private val client by lazy {
        OkHttpClient.Builder()
            .socketFactory(sockets)
            .dns(dns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Chính sách tile OSM: User-Agent phải nói rõ app nào
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
            .also { clientMade = true }
    }

    /**
     * Một loader cho cả app: mỗi ImageLoader của Coil tự đăng ký callback hệ thống và chỉ gỡ
     * khi shutdown, nên không tạo mới theo từng mạng. Theo dõi mạng đã có [network].
     * OSM yêu cầu cache phía app; tile ít đổi nên giữ tới khi bị đẩy ra (không xét Cache-Control).
     */
    val loader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient { client }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("tiles"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(24 * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .networkObserverEnabled(false)
            .allowHardware(false)
            .build()
    }

    companion object {
        const val MAX_ZOOM = 19
        fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
        const val ATTRIBUTION = "© OpenStreetMap"
    }
}

/** Toạ độ Web Mercator chuẩn hoá: x, y ∈ [0, 1), (0, 0) = góc tây bắc. */
data class World(val x: Double, val y: Double)

fun world(lat: Double, lon: Double): World {
    val phi = lat.coerceIn(-85.0511, 85.0511) * PI / 180
    return World((lon + 180) / 360, (1 - ln(tan(phi) + 1 / cos(phi)) / PI) / 2)
}
