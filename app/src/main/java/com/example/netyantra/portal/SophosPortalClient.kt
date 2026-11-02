package com.example.netyantra.portal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PortalResponse(
    val statusCode: Int,
    val isSuccess: Boolean,
    val status: String,
    val message: String,
    val rawResponseBody: String,
    val connectivityResult: String? = null
) {
    val internetWorking: Boolean
        get() = connectivityResult == "Internet working"

    val internetStatus: String?
        get() = connectivityResult
}

object SophosPortalClient {
    private const val TAG = "SophosPortal"
    private const val PORTAL_URL = "http://172.16.16.16:8090/login.xml"
    private const val CONNECTIVITY_CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

    /**
     * Executes an operation bound strictly to a Wi-Fi network using ConnectivityManager.requestNetwork.
     */
    private suspend fun <T> withWifiNetwork(
        context: Context,
        timeoutMs: Long = 30000L,
        block: suspend (Network, ConnectivityManager) -> T
    ): T {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        var registeredCallback: ConnectivityManager.NetworkCallback? = null

        return try {
            withTimeout(timeoutMs) {
                val network = suspendCancellableCoroutine { continuation ->
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        private val resumed = AtomicBoolean(false)

                        override fun onAvailable(network: Network) {
                            if (resumed.compareAndSet(false, true)) {
                                Log.d(TAG, "Wi-Fi network found: $network")
                                continuation.resume(network)
                            }
                        }

                        override fun onUnavailable() {
                            if (resumed.compareAndSet(false, true)) {
                                continuation.resumeWithException(IOException("Wi-Fi network unavailable"))
                            }
                        }
                    }

                    registeredCallback = callback
                    try {
                        connectivityManager.requestNetwork(request, callback)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return@suspendCancellableCoroutine
                    }

                    continuation.invokeOnCancellation {
                        try {
                            connectivityManager.unregisterNetworkCallback(callback)
                        } catch (_: Exception) {}
                    }
                }

                block(network, connectivityManager)
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Wi-Fi network not found within ${timeoutMs / 1000}s. Ensure Wi-Fi is connected.")
        } finally {
            registeredCallback?.let { cb ->
                try {
                    connectivityManager.unregisterNetworkCallback(cb)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Builds an OkHttpClient bound to the Wi-Fi network with redirects disabled.
     */
    private fun buildProbeClient(network: Network): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Performs captive portal login.
     * POST http://172.16.16.16:8090/login.xml
     * Form fields: mode=191, username, password, a=<epoch_ms>, producttype=0
     * Success is when extracted <status> equals "live".
     *
     * After successful login:
     * - Calls connectivityManager.reportNetworkConnectivity(network, true)
     * - Polls http://connectivitycheck.gstatic.com/generate_204 bound to that Wi-Fi network
     *   (with OkHttp redirects disabled) every second for up to 10 seconds.
     * - Shows "Internet working" when it returns 204, otherwise "Logged in, but internet not confirmed".
     */
    suspend fun login(
        context: Context,
        username: String,
        password: String,
        onProgress: ((String) -> Unit)? = null
    ): PortalResponse = withContext(Dispatchers.IO) {
        val a = System.currentTimeMillis().toString()

        try {
            withWifiNetwork(context) { network, connectivityManager ->
                val okHttpClient = OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()

                val formBody = FormBody.Builder()
                    .add("mode", "191")
                    .add("username", username)
                    .add("password", password)
                    .add("a", a)
                    .add("producttype", "0")
                    .build()

                val request = Request.Builder()
                    .url(PORTAL_URL)
                    .post(formBody)
                    .build()

                // Logcat logging: NEVER log the password!
                Log.d(TAG, "Request sent: POST $PORTAL_URL, mode=191, username=$username, a=$a, producttype=0")

                var statusCode = 0
                var rawBody = ""
                var status = ""
                var message = ""
                var isSuccess = false

                okHttpClient.newCall(request).execute().use { response ->
                    statusCode = response.code
                    rawBody = response.body?.string().orEmpty()
                    status = extractStatus(rawBody)
                    message = extractMessage(rawBody)

                    // Success is when status equals live (case-insensitive, trimmed)
                    isSuccess = status == "live"

                    Log.d(TAG, "Response code: $statusCode")
                    Log.d(TAG, "Extracted status: \"$status\"")
                    Log.d(TAG, "Raw response body:\n$rawBody")
                    if (isSuccess) {
                        Log.d(TAG, "Result: Success (status=\"$status\")")
                    } else {
                        Log.d(TAG, "Result: Failed (status=\"$status\", message=\"$message\")")
                    }
                }

                var connectivityResult: String? = null

                if (isSuccess) {
                    // Call connectivityManager.reportNetworkConnectivity(network, true) for the Wi-Fi network
                    Log.d(TAG, "Calling connectivityManager.reportNetworkConnectivity(network, true)")
                    try {
                        connectivityManager.reportNetworkConnectivity(network, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "reportNetworkConnectivity error: ${e.message}")
                    }

                    // Poll http://connectivitycheck.gstatic.com/generate_204 with redirects disabled for up to 10 seconds
                    onProgress?.invoke("Checking...")
                    val probeClient = buildProbeClient(network)
                    val probeRequest = Request.Builder()
                        .url(CONNECTIVITY_CHECK_URL)
                        .get()
                        .build()

                    val maxDurationMs = 10_000L
                    val startTime = System.currentTimeMillis()
                    var attempt = 1
                    var got204 = false

                    while (System.currentTimeMillis() - startTime < maxDurationMs) {
                        onProgress?.invoke("Checking...")
                        try {
                            Log.d(TAG, "Polling connectivitycheck.gstatic.com (redirects disabled)... attempt $attempt")
                            probeClient.newCall(probeRequest).execute().use { probeResponse ->
                                val code = probeResponse.code
                                Log.d(TAG, "Connectivity probe response code: $code")
                                if (code == 204) {
                                    got204 = true
                                    Log.d(TAG, "Connectivity probe returned 204")
                                    return@use
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Connectivity probe attempt $attempt exception: ${e.message}")
                        }

                        if (got204) break

                        val elapsed = System.currentTimeMillis() - startTime
                        val remaining = maxDurationMs - elapsed
                        if (remaining > 0) {
                            delay(minOf(1000L, remaining))
                        }
                        attempt++
                    }

                    connectivityResult = if (got204) {
                        Log.d(TAG, "Post-login connectivity result: Internet working")
                        "Internet working"
                    } else {
                        Log.d(TAG, "Post-login connectivity result: Logged in, but internet not confirmed")
                        "Logged in, but internet not confirmed"
                    }
                }

                PortalResponse(
                    statusCode = statusCode,
                    isSuccess = isSuccess,
                    status = status,
                    message = message,
                    rawResponseBody = rawBody,
                    connectivityResult = connectivityResult
                )
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Result: Failed (status=\"\", message=\"$errorMsg\")", e)
            PortalResponse(
                statusCode = -1,
                isSuccess = false,
                status = "",
                message = errorMsg,
                rawResponseBody = "",
                connectivityResult = null
            )
        }
    }

    /**
     * Performs captive portal logout.
     * POST http://172.16.16.16:8090/login.xml
     * Form fields: mode=193, username, a=<epoch_ms>, producttype=0
     * Success is when extracted <status> equals "login".
     *
     * After successful logout:
     * - Calls connectivityManager.reportNetworkConnectivity(network, false)
     * - Polls http://connectivitycheck.gstatic.com/generate_204 bound to Wi-Fi network
     *   (with OkHttp redirects disabled) every second for up to 10 seconds.
     * - If response is not 204 (redirect, other code or error), shows "Logged out, Wi-Fi internet blocked".
     * - If it still returns 204 after 10 seconds, shows "Logged out, but Wi-Fi still has internet. Session may still be active."
     */
    suspend fun logout(
        context: Context,
        username: String,
        onProgress: ((String) -> Unit)? = null
    ): PortalResponse = withContext(Dispatchers.IO) {
        val a = System.currentTimeMillis().toString()

        try {
            withWifiNetwork(context) { network, connectivityManager ->
                val okHttpClient = OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()

                val formBody = FormBody.Builder()
                    .add("mode", "193")
                    .add("username", username)
                    .add("a", a)
                    .add("producttype", "0")
                    .build()

                val request = Request.Builder()
                    .url(PORTAL_URL)
                    .post(formBody)
                    .build()

                Log.d(TAG, "Request sent: POST $PORTAL_URL, mode=193, username=$username, a=$a, producttype=0")

                var statusCode = 0
                var rawBody = ""
                var status = ""
                var message = ""
                var isSuccess = false

                okHttpClient.newCall(request).execute().use { response ->
                    statusCode = response.code
                    rawBody = response.body?.string().orEmpty()
                    status = extractStatus(rawBody)
                    message = extractMessage(rawBody)

                    isSuccess = status == "login"

                    Log.d(TAG, "Response code: $statusCode")
                    Log.d(TAG, "Extracted status: \"$status\"")
                    Log.d(TAG, "Raw response body:\n$rawBody")
                    if (isSuccess) {
                        Log.d(TAG, "Result: Success (status=\"$status\")")
                    } else {
                        Log.d(TAG, "Result: Failed (status=\"$status\", message=\"$message\")")
                    }
                }

                var connectivityResult: String? = null

                if (isSuccess) {
                    // Call connectivityManager.reportNetworkConnectivity(network, false) for the Wi-Fi network
                    Log.d(TAG, "Calling connectivityManager.reportNetworkConnectivity(network, false)")
                    try {
                        connectivityManager.reportNetworkConnectivity(network, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "reportNetworkConnectivity error: ${e.message}")
                    }

                    // Poll http://connectivitycheck.gstatic.com/generate_204 with redirects disabled for up to 10 seconds
                    onProgress?.invoke("Checking...")
                    val probeClient = buildProbeClient(network)
                    val probeRequest = Request.Builder()
                        .url(CONNECTIVITY_CHECK_URL)
                        .get()
                        .build()

                    val maxDurationMs = 10_000L
                    val startTime = System.currentTimeMillis()
                    var attempt = 1
                    var blocked = false

                    while (System.currentTimeMillis() - startTime < maxDurationMs) {
                        onProgress?.invoke("Checking...")
                        try {
                            Log.d(TAG, "Polling connectivitycheck.gstatic.com (redirects disabled)... attempt $attempt")
                            probeClient.newCall(probeRequest).execute().use { probeResponse ->
                                val code = probeResponse.code
                                Log.d(TAG, "Connectivity probe response code: $code")
                                if (code != 204) {
                                    blocked = true
                                    Log.d(TAG, "Connectivity probe returned non-204 code: $code (internet blocked)")
                                    return@use
                                }
                            }
                        } catch (e: Exception) {
                            blocked = true
                            Log.d(TAG, "Connectivity probe attempt $attempt exception: ${e.message} (internet blocked)")
                        }

                        if (blocked) break

                        val elapsed = System.currentTimeMillis() - startTime
                        val remaining = maxDurationMs - elapsed
                        if (remaining > 0) {
                            delay(minOf(1000L, remaining))
                        }
                        attempt++
                    }

                    connectivityResult = if (blocked) {
                        Log.d(TAG, "Post-logout connectivity result: Logged out, Wi-Fi internet blocked")
                        "Logged out, Wi-Fi internet blocked"
                    } else {
                        Log.d(TAG, "Post-logout connectivity result: Logged out, but Wi-Fi still has internet. Session may still be active.")
                        "Logged out, but Wi-Fi still has internet. Session may still be active."
                    }
                }

                PortalResponse(
                    statusCode = statusCode,
                    isSuccess = isSuccess,
                    status = status,
                    message = message,
                    rawResponseBody = rawBody,
                    connectivityResult = connectivityResult
                )
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Result: Failed (status=\"\", message=\"$errorMsg\")", e)
            PortalResponse(
                statusCode = -1,
                isSuccess = false,
                status = "",
                message = errorMsg,
                rawResponseBody = "",
                connectivityResult = null
            )
        }
    }

    /**
     * Extracts the content of the <status> tag from the raw response body,
     * including when it is wrapped in <![CDATA[ ... ]]>.
     * Uses regex: <status>\s*(?:<!\[CDATA\[)?\s*([^\]<]*?)\s*(?:\]\]>)?\s*</status>
     * and trims and lowercases the result.
     */
    fun extractStatus(xml: String): String {
        val regex = Regex(
            """<status>\s*(?:<!\[CDATA\[)?\s*([^\]<]*?)\s*(?:\]\]>)?\s*</status>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }

    /**
     * Extracts the message text from the <message> tag, handling optional CDATA blocks.
     */
    fun extractMessage(xml: String): String {
        val regex = Regex(
            """<message(?:\s+[^>]*)?>\s*(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</message>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }
}
