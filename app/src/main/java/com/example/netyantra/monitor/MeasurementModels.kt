package com.example.netyantra.monitor

import com.example.netyantra.decision.DecisionEvaluation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MeasurementConfig {
    const val DEFAULT_INTERVAL_SECONDS = 30L
    const val LATENCY_URL_PRIMARY = "http://connectivitycheck.gstatic.com/generate_204"
    const val LATENCY_URL_FALLBACK = "https://www.google.com/generate_204"
    // Cloudflare speed test endpoint serving a small 200 KB (204,800 bytes) payload
    const val DOWNLOAD_URL_PRIMARY = "https://speed.cloudflare.com/__down?bytes=204800"
    const val DOWNLOAD_URL_FALLBACK = "https://httpbin.org/bytes/204800"
    const val DOWNLOAD_TIMEOUT_SECONDS = 5L
    const val TARGET_BYTES = 204800L // ~200 KB
}

data class NetworkTestResult(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long? = null,
    val downloadSpeedKbps: Double? = null,
    val bytesDownloaded: Long = 0,
    val durationMs: Long = 0,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val speedMbps: Double?
        get() = downloadSpeedKbps?.let { it / 1000.0 }
}

data class NetworkMetrics(
    val transportType: TransportType,
    val isAvailable: Boolean = false,
    val isTesting: Boolean = false,
    val latestResult: NetworkTestResult? = null,
    val history: List<NetworkTestResult> = emptyList() // Last 10 results
) {
    fun withNewResult(result: NetworkTestResult): NetworkMetrics {
        val newHistory = (history + result).takeLast(10)
        return copy(
            latestResult = result,
            history = newHistory,
            isTesting = false
        )
    }
}

data class MeasurementState(
    val isServiceRunning: Boolean = false,
    val intervalSeconds: Long = MeasurementConfig.DEFAULT_INTERVAL_SECONDS,
    val wifiMetrics: NetworkMetrics = NetworkMetrics(TransportType.WIFI),
    val cellularMetrics: NetworkMetrics = NetworkMetrics(TransportType.CELLULAR),
    val decisionEvaluation: DecisionEvaluation = DecisionEvaluation(),
    val lastRunTimestamp: Long? = null,
    val activeSummary: String = "Monitoring Stopped"
)
