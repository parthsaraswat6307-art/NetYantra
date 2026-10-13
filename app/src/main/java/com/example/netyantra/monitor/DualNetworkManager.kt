package com.example.netyantra.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.netyantra.data.MeasurementRepository

class DualNetworkManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var wifiNetwork: Network? = null
        private set

    @Volatile
    var cellularNetwork: Network? = null
        private set

    private var isRegistered = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            MeasurementRepository.updateWifiAvailability(true)
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                MeasurementRepository.updateWifiAvailability(false)
            }
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetwork = network
            MeasurementRepository.updateCellularAvailability(true)
        }

        override fun onLost(network: Network) {
            if (cellularNetwork == network) {
                cellularNetwork = null
                MeasurementRepository.updateCellularAvailability(false)
            }
        }

        override fun onUnavailable() {
            cellularNetwork = null
            MeasurementRepository.updateCellularAvailability(false)
        }
    }

    /**
     * Registers network callbacks. Specifically uses [requestNetwork] for cellular
     * to keep mobile data routing active even when Wi-Fi is the primary interface.
     */
    fun startListening() {
        if (isRegistered) return

        val wifiRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cellularRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        try {
            // Register callback for Wi-Fi
            connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            // Use requestNetwork to force cellular network to remain available when Wi-Fi is active
            connectivityManager.requestNetwork(cellularRequest, cellularCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isRegistered = true
    }

    fun stopListening() {
        if (!isRegistered) return

        try {
            connectivityManager.unregisterNetworkCallback(wifiCallback)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }

        try {
            connectivityManager.unregisterNetworkCallback(cellularCallback)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }

        wifiNetwork = null
        cellularNetwork = null
        MeasurementRepository.updateWifiAvailability(false)
        MeasurementRepository.updateCellularAvailability(false)
        isRegistered = false
    }
}
