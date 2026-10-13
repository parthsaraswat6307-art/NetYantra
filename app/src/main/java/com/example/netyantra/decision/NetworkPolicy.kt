package com.example.netyantra.decision

/**
 * User or system preferences for network interface selection.
 */
enum class RoutingPreference {
    AUTO,
    PREFER_WIFI,
    PREFER_MOBILE,
    FAILOVER
}

/**
 * Result of evaluating current network state against policies.
 */
data class DecisionResult(
    val recommendedInterface: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
