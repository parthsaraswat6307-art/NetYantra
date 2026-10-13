package com.example.netyantra.monitor

/**
 * Represents the current connectivity snapshot of network interfaces.
 */
data class ConnectivityState(
    val isWifiConnected: Boolean = false,
    val wifiSsid: String? = null,
    val wifiRssi: Int = 0,
    val isMobileConnected: Boolean = false,
    val mobileNetworkType: String? = null,
    val isRoaming: Boolean = false,
    val activeTransport: TransportType = TransportType.NONE
)

enum class TransportType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN
}
