package com.example.netyantra.monitor

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface contract for monitoring network connectivity changes.
 */
interface NetworkMonitor {
    val connectivityState: StateFlow<ConnectivityState>
    fun startMonitoring()
    fun stopMonitoring()
}
