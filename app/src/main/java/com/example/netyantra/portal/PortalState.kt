package com.example.netyantra.portal

/**
 * State representing captive portal detection status.
 */
sealed interface PortalState {
    data object None : PortalState
    data object Checking : PortalState
    data class Detected(val loginUrl: String, val ssid: String) : PortalState
    data object Authenticated : PortalState
    data class Error(val message: String) : PortalState
}
