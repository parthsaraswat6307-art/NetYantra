package com.example.netyantra.portal

import okhttp3.OkHttpClient

/**
 * Interface contract for detecting captive portals using OkHttp.
 */
interface CaptivePortalDetector {
    suspend fun checkForCaptivePortal(client: OkHttpClient): PortalState
}
