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

// Runs for the whole app process (see TodoApp), not one screen, so leaving home with the app
// closed still turns the place context off, for the widget too.
class WifiContextMonitor(
    private val connectivityManager: ConnectivityManager,
    private val wifiManager: WifiManager,
    private val contextDao: TaskContextDao,
    private val scope: CoroutineScope
) {
    private val wifiNetworks = mutableSetOf<Network>()
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(wifiNetworks) { wifiNetworks += network }
            refresh(onWifi = true)
        }
        override fun onLost(network: Network) {
            val any = synchronized(wifiNetworks) { wifiNetworks -= network; wifiNetworks.isNotEmpty() }
            refresh(onWifi = any)
        }
    }

    fun start() {
        if (started) return
        started = true
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        // The callback only fires for wifi that's up, so off wifi at start it would never correct a stale "home".
        refresh(onWifi = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    // Off wifi, no place holds. On wifi, the network's name decides, unless Android hides it (no
    // location access right now, e.g. in the background): then nothing changes rather than guessing.
    internal fun refresh(onWifi: Boolean) {
        scope.launch {
            val ssid = wifiManager.connectionInfo?.ssid?.trim('"')?.takeUnless { it == WifiManager.UNKNOWN_SSID }
            val current = if (!onWifi) null else ssid ?: return@launch
            contextDao.getAll()
                .filter { it.type == ContextType.PLACE }
                .forEach { ctx -> contextDao.setSatisfied(ctx.id, ctx.wifiSsid == current) }
        }
    }
}
