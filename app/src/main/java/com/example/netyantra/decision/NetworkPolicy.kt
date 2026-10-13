package com.example.netyantra.decision

enum class RoutingPreference {
    AUTO,
    PREFER_WIFI,
    PREFER_MOBILE,
    FAILOVER
}

enum class NetworkRecommendation(val displayName: String) {
    EVALUATING("Evaluating"),
    WIFI_IS_BETTER("Wi-Fi is better"),
    MOBILE_IS_BETTER("Mobile data is better")
}

data class DecisionThresholds(
    val speedThresholdPercent: Int = 30, // Default 30% lower speed
    val latencyMultiplier: Double = 2.0,   // Default 2x higher latency
    val consecutiveWorseRequired: Int = 3, // Requires 3 consecutive checks
    val hysteresisSpeedPercent: Int = 10,  // Wi-Fi must be >= 110% of mobile to flip back
    val hysteresisChecksRequired: Int = 2  // Requires 2 consecutive checks to flip back
)

data class DecisionEvaluation(
    val recommendation: NetworkRecommendation = NetworkRecommendation.EVALUATING,
    val reason: String = "Collecting initial measurements",
    val avgWifiSpeedMbps: Double? = null,
    val avgWifiLatencyMs: Double? = null,
    val avgMobileSpeedMbps: Double? = null,
    val avgMobileLatencyMs: Double? = null,
    val consecutiveWorseCount: Int = 0,
    val consecutiveBetterCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class DecisionResult(
    val recommendedInterface: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
