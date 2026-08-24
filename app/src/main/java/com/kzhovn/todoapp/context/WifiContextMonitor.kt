package com.kzhovn.todoapp.context

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContextDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WifiContextMonitor(
    private val connectivityManager: ConnectivityManager,
    private val wifiManager: WifiManager,
    private val contextDao: TaskContextDao,
    private val scope: CoroutineScope
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun stop() {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    internal fun refresh() {
        scope.launch {
            val currentSsid = wifiManager.connectionInfo?.ssid?.trim('"')
            contextDao.getAll()
                .filter { it.type == ContextType.PLACE }
                .forEach { ctx -> contextDao.setSatisfied(ctx.id, ctx.wifiSsid == currentSsid) }
        }
    }
}
