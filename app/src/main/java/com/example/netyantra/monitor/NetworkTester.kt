package com.example.netyantra.monitor

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class NetworkTester {

    /**
     * Builds an OkHttpClient whose sockets and DNS resolution are explicitly bound
     * to the provided Android [Network] instance.
     */
    private fun buildClientForNetwork(network: Network, timeoutSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        network.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Measures latency (time to first byte) and download speed (~200 KB payload)
     * strictly bound to the specified [network].
     */
    suspend fun executeMeasurement(network: Network?): NetworkTestResult = withContext(Dispatchers.IO) {
        if (network == null) {
            return@withContext NetworkTestResult(
                isSuccess = false,
                errorMessage = "Network interface unavailable"
            )
        }

        val client = buildClientForNetwork(
            network = network,
            timeoutSeconds = MeasurementConfig.DOWNLOAD_TIMEOUT_SECONDS
        )

        // 1. Measure Latency (Time to First Byte)
        val latencyResult = measureLatency(client)
        if (!latencyResult.isSuccess) {
            return@withContext NetworkTestResult(
                isSuccess = false,
                errorMessage = latencyResult.errorMessage ?: "Latency test failed"
            )
        }

        // 2. Measure Download Speed (Small ~200 KB payload, 5s max)
        val downloadResult = measureDownloadSpeed(client)

        NetworkTestResult(
            latencyMs = latencyResult.latencyMs,
            downloadSpeedKbps = downloadResult.speedKbps,
            bytesDownloaded = downloadResult.bytesRead,
            durationMs = downloadResult.durationMs,
            isSuccess = downloadResult.isSuccess,
            errorMessage = downloadResult.errorMessage
        )
    }

    private fun measureLatency(client: OkHttpClient): LatencyTestInternal {
        // Try primary lightweight endpoint
        return executeLatencyRequest(client, MeasurementConfig.LATENCY_URL_PRIMARY).takeIf { it.isSuccess }
            ?: executeLatencyRequest(client, MeasurementConfig.LATENCY_URL_FALLBACK)
    }

    private fun executeLatencyRequest(client: OkHttpClient, url: String): LatencyTestInternal {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NetYantra-Monitor/1.0")
            .header("Cache-Control", "no-cache")
            .build()

        val startTime = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                if (response.isSuccessful || response.code == 204) {
                    LatencyTestInternal(isSuccess = true, latencyMs = elapsedMs)
                } else {
                    LatencyTestInternal(isSuccess = false, errorMessage = "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            LatencyTestInternal(isSuccess = false, errorMessage = e.message ?: "Connection error")
        }
    }

    private fun measureDownloadSpeed(client: OkHttpClient): DownloadTestInternal {
        // Try primary download host, or fallback
        val primary = executeDownloadRequest(client, MeasurementConfig.DOWNLOAD_URL_PRIMARY)
        if (primary.isSuccess) return primary
        return executeDownloadRequest(client, MeasurementConfig.DOWNLOAD_URL_FALLBACK)
    }

    private fun executeDownloadRequest(client: OkHttpClient, url: String): DownloadTestInternal {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NetYantra-Monitor/1.0")
            .header("Cache-Control", "no-cache")
            .build()

        val startTime = System.nanoTime()
        var totalBytesRead = 0L

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return DownloadTestInternal(
                        isSuccess = false,
                        errorMessage = "Download HTTP ${response.code}"
                    )
                }

                val stream: InputStream = body.byteStream()
                val buffer = ByteArray(8192)
                var bytes: Int
                val deadline = System.currentTimeMillis() + (MeasurementConfig.DOWNLOAD_TIMEOUT_SECONDS * 1000)

                while (stream.read(buffer).also { bytes = it } != -1) {
                    totalBytesRead += bytes
                    // Safeguard: terminate if elapsed time exceeds 5 seconds
                    if (System.currentTimeMillis() >= deadline) {
                        break
                    }
                }

                val durationMs = maxOf(1L, (System.nanoTime() - startTime) / 1_000_000)
                val durationSec = durationMs / 1000.0
                // kilobits per second = (bytes * 8) / (seconds) / 1000
                val speedKbps = if (durationSec > 0) (totalBytesRead * 8.0) / durationSec / 1000.0 else 0.0

                DownloadTestInternal(
                    isSuccess = totalBytesRead > 0,
                    bytesRead = totalBytesRead,
                    durationMs = durationMs,
                    speedKbps = speedKbps,
                    errorMessage = if (totalBytesRead > 0) null else "No bytes received"
                )
            }
        } catch (e: Exception) {
            val durationMs = maxOf(1L, (System.nanoTime() - startTime) / 1_000_000)
            val durationSec = durationMs / 1000.0
            val speedKbps = if (totalBytesRead > 0 && durationSec > 0) (totalBytesRead * 8.0) / durationSec / 1000.0 else null

            DownloadTestInternal(
                isSuccess = totalBytesRead > 1024, // Consider partial success if some bytes were measured before timeout
                bytesRead = totalBytesRead,
                durationMs = durationMs,
                speedKbps = speedKbps,
                errorMessage = e.message ?: "Download interrupted"
            )
        }
    }

    private data class LatencyTestInternal(
        val isSuccess: Boolean,
        val latencyMs: Long? = null,
        val errorMessage: String? = null
    )

    private data class DownloadTestInternal(
        val isSuccess: Boolean,
        val bytesRead: Long = 0,
        val durationMs: Long = 0,
        val speedKbps: Double? = null,
        val errorMessage: String? = null
    )
}
