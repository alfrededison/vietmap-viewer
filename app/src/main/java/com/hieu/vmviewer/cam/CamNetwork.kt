package com.hieu.vmviewer.cam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WiFi của camera không có Internet, nên Android hay tự đẩy traffic sang 4G.
 * Ta "giữ" mạng WiFi đó và bind cả process vào nó, để OkHttp / ExoPlayer / Coil
 * đều đi thẳng tới 192.168.1.254.
 */
class CamNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _wifi = MutableStateFlow<Network?>(null)
    val wifi: StateFlow<Network?> = _wifi

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cm.bindProcessToNetwork(network)
            _wifi.value = network
        }

        override fun onLost(network: Network) {
            if (_wifi.value == network) {
                cm.bindProcessToNetwork(null)
                _wifi.value = null
            }
        }
    }

    fun start() {
        if (registered) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(req, callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        cm.bindProcessToNetwork(null)
        registered = false
        _wifi.value = null
    }
}
