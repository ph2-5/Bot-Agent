package com.qbot.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 网络可用性监听。
 *
 * 为什么不用静态广播：Android 7.0（API 24）起，
 * 在 Manifest 里静态注册的 `CONNECTIVITY_CHANGE` 不再生效，
 * 只有动态注册的接收者能收到。因此这里统一用 [ConnectivityManager.NetworkCallback]，
 * 它比广播响应更快，也无需额外权限。
 *
 * @param onAvailable 网络恢复可用时回调。会做一次小延迟去重——
 *                    网络切换瞬间（如 Wi-Fi 切 4G）会连续触发多次回调，
 *                    直接重连会造成连接抖动。
 */
class NetworkMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onAvailable: () -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    private var notifyJob: kotlinx.coroutines.Job? = null

    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    fun start() {
        if (callback != null) return

        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleNotify()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = scheduleNotify()
            }

        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
        }
    }

    fun stop() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null
        notifyJob?.cancel()
        notifyJob = null
    }

    /**
     * 延迟 1.5 秒再通知。
     *
     * 网络刚恢复时往往还拿不到可用路由，立刻重连大概率失败并白白消耗一次重试计数。
     */
    private fun scheduleNotify() {
        notifyJob?.cancel()
        notifyJob =
            scope.launch {
                delay(1_500L)
                onAvailable()
            }
    }

    /** 当前是否已有可用网络。 */
    fun isOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun isOnlineCompat(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.activeNetwork?.let { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } ?: false
            } else {
                cm.activeNetworkInfo?.isConnected == true
            }
        }
    }
}
