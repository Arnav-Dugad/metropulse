package io.github.arnavdugad.metropulse.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Whether the phone has a connection to the internet, updated as networks come and go. */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    val online = remember { mutableStateOf(isOnline(manager)) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online.value = true
            }

            override fun onLost(network: Network) {
                online.value = isOnline(manager)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun isOnline(manager: ConnectivityManager?): Boolean {
    val network = manager?.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/** For code outside Compose. */
fun Context.isOnline(): Boolean = isOnline(getSystemService(ConnectivityManager::class.java))
