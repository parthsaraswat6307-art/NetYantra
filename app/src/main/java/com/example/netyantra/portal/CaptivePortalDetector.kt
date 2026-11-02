package com.example.netyantra.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Interface contract for detecting captive portals using OkHttp.
 */
interface CaptivePortalDetector {
    suspend fun checkForCaptivePortal(client: OkHttpClient): PortalState
}

/**
 * Default implementation of [CaptivePortalDetector] using HTTP generate_204 and captive probe endpoints.
 * A redirect (HTTP 3xx) or non-204 HTTP response indicates a captive portal is present.
 */
class DefaultCaptivePortalDetector(
    private val primaryUrl: String = "http://connectivitycheck.gstatic.com/generate_204",
    private val secondaryUrl: String = "http://clients3.google.com/generate_204",
    private val appleProbeUrl: String = "http://captive.apple.com/hotspot-detect.html",
    private val fallbackUrl: String = "http://www.google.com/generate_204"
) : CaptivePortalDetector {

    override suspend fun checkForCaptivePortal(client: OkHttpClient): PortalState = withContext(Dispatchers.IO) {
        // Enforce no redirects so we can inspect redirect responses (3xx) directly and get Location header
        val detectionClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val probeEndpoints = listOf(primaryUrl, secondaryUrl, appleProbeUrl, fallbackUrl)
        var lastError: PortalState.Error? = null

        for (url in probeEndpoints) {
            val result = executeCheck(detectionClient, url)
            when (result) {
                is PortalState.None -> return@withContext result
                is PortalState.Detected -> return@withContext result
                is PortalState.Error -> lastError = result
                else -> {}
            }
        }

        lastError ?: PortalState.Error("Captive portal probe failed: connection blocked by network")
    }

    private fun executeCheck(client: OkHttpClient, url: String): PortalState {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; NetYantra)")
            .header("Cache-Control", "no-cache")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 204) {
                    // HTTP 204 No Content indicates open internet connectivity
                    PortalState.None
                } else if (code in 300..399) {
                    // HTTP Redirect indicates a captive portal is intercepting traffic
                    val location = response.header("Location") ?: ""
                    PortalState.Detected(loginUrl = location, ssid = "")
                } else if (url.contains("hotspot-detect") && code == 200) {
                    // Apple probe returns "Success" when genuinely connected to open internet
                    val body = response.body?.string() ?: ""
                    if (body.contains("<TITLE>Success</TITLE>", ignoreCase = true) || body.trim() == "Success") {
                        PortalState.None
                    } else {
                        val location = response.header("Location") ?: response.request.url.toString()
                        PortalState.Detected(loginUrl = location, ssid = "")
                    }
                } else {
                    // Non-204 HTTP status (e.g. 200 with portal HTML page, 403, 511)
                    val body = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                    val location = extractPortalLoginUrl(
                        locationHeader = response.header("Location"),
                        body = body,
                        requestUrl = response.request.url.toString()
                    )
                    PortalState.Detected(loginUrl = location, ssid = "")
                }
            }
        } catch (e: Exception) {
            PortalState.Error("Captive portal probe failed: ${e.message ?: "Connection error"}")
        }
    }

    /**
     * Extracts the captive portal login URL from HTTP headers or HTML content.
     * Prevents returning generate_204 probe URLs as the login destination.
     */
    fun extractPortalLoginUrl(locationHeader: String?, body: String, requestUrl: String): String {
        if (!locationHeader.isNullOrBlank()) {
            return locationHeader.trim()
        }

        if (body.isNotBlank()) {
            // 1. Script redirection: location.href = "..." or window.location = "..."
            val scriptMatch = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            if (!scriptMatch.isNullOrBlank()) return scriptMatch.trim()

            // 2. Meta refresh: <meta http-equiv="refresh" content="...url=...">
            val metaMatch = Regex("""<meta[^>]*http-equiv=['"]?refresh['"]?[^>]*content=['"][^'"]*url=([^'"\s>]+)['"]""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            if (!metaMatch.isNullOrBlank()) return metaMatch.trim()

            // 3. Form action: <form action="...">
            val formMatch = Regex("""<form[^>]*action=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            if (!formMatch.isNullOrBlank()) return formMatch.trim()

            // 4. Common Cyberoam / Sophos signature (port 8090)
            val sophosMatch = Regex("""https?://[a-zA-Z0-9.\-_]+:8090[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .find(body)?.value
            if (!sophosMatch.isNullOrBlank()) return sophosMatch.trim()
        }

        // Never return generate_204 or hotspot-detect probe URLs as the target login URL
        return if (requestUrl.contains("generate_204") || requestUrl.contains("hotspot-detect")) {
            ""
        } else {
            requestUrl
        }
    }
}
