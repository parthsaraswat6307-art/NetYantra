package com.example.netyantra.decision

import com.example.netyantra.monitor.NetworkTestResult
import java.util.Locale

class NetworkDecisionEngine(
    var thresholds: DecisionThresholds = DecisionThresholds()
) {
    var currentRecommendation: NetworkRecommendation = NetworkRecommendation.EVALUATING
        private set

    var consecutiveWorseCount: Int = 0
        private set

    var consecutiveBetterCount: Int = 0
        private set

    /**
     * Evaluates the last 3 measurements of Wi-Fi vs Mobile data.
     * Applies consecutive threshold triggers and hysteresis to prevent flapping.
     */
    fun evaluate(
        wifiHistory: List<NetworkTestResult>,
        mobileHistory: List<NetworkTestResult>
    ): DecisionEvaluation {
        val successfulWifi = wifiHistory.filter { it.isSuccess && it.speedMbps != null && it.latencyMs != null }
        val successfulMobile = mobileHistory.filter { it.isSuccess && it.speedMbps != null && it.latencyMs != null }

        val recentWifi = successfulWifi.takeLast(3)
        val recentMobile = successfulMobile.takeLast(3)

        if (recentWifi.size < 3 || recentMobile.size < 3) {
            val sampleReason = "Need 3 samples on both networks (Wi-Fi: ${recentWifi.size}/3, Mobile: ${recentMobile.size}/3)"
            return DecisionEvaluation(
                recommendation = currentRecommendation,
                reason = sampleReason,
                consecutiveWorseCount = consecutiveWorseCount,
                consecutiveBetterCount = consecutiveBetterCount
            )
        }

        val avgWifiSpeed = recentWifi.map { it.speedMbps!! }.average()
        val avgWifiLatency = recentWifi.map { it.latencyMs!!.toDouble() }.average()
        val avgMobileSpeed = recentMobile.map { it.speedMbps!! }.average()
        val avgMobileLatency = recentMobile.map { it.latencyMs!!.toDouble() }.average()

        // 1. Degradation threshold check:
        // Wi-Fi speed is worse if < (1 - threshold%) of mobile speed
        val speedFactor = 1.0 - (thresholds.speedThresholdPercent / 100.0)
        val isSpeedWorse = avgWifiSpeed < (avgMobileSpeed * speedFactor)

        // Wi-Fi latency is worse if > (multiplier * mobile latency)
        val isLatencyWorse = avgWifiLatency > (avgMobileLatency * thresholds.latencyMultiplier)

        val isWifiWorse = isSpeedWorse || isLatencyWorse

        val summaryWifi = String.format(Locale.US, "%.1f Mbps / %.0f ms", avgWifiSpeed, avgWifiLatency)
        val summaryMobile = String.format(Locale.US, "%.1f Mbps / %.0f ms", avgMobileSpeed, avgMobileLatency)

        // 2. State transition with Hysteresis:
        val evaluationReason: String

        if (currentRecommendation != NetworkRecommendation.MOBILE_IS_BETTER) {
            if (isWifiWorse) {
                consecutiveWorseCount++
                consecutiveBetterCount = 0

                if (consecutiveWorseCount >= thresholds.consecutiveWorseRequired) {
                    currentRecommendation = NetworkRecommendation.MOBILE_IS_BETTER
                    evaluationReason = "Mobile data is better: Wi-Fi worse for $consecutiveWorseCount checks in a row (Wi-Fi: $summaryWifi vs Mobile: $summaryMobile)"
                } else {
                    if (currentRecommendation == NetworkRecommendation.EVALUATING) {
                        currentRecommendation = NetworkRecommendation.WIFI_IS_BETTER
                    }
                    evaluationReason = "Wi-Fi underperforming (Check $consecutiveWorseCount of ${thresholds.consecutiveWorseRequired} before switching)"
                }
            } else {
                consecutiveWorseCount = 0
                consecutiveBetterCount = 0
                currentRecommendation = NetworkRecommendation.WIFI_IS_BETTER
                evaluationReason = "Wi-Fi is better / adequate (Wi-Fi: $summaryWifi vs Mobile: $summaryMobile)"
            }
        } else {
            // Already in MOBILE_IS_BETTER. Apply HYSTERESIS:
            // It only flips back when Wi-Fi is CLEARLY better (>= +hysteresis% speed AND <= latency)
            // for consecutive checks.
            val hysteresisFactor = 1.0 + (thresholds.hysteresisSpeedPercent / 100.0)
            val isWifiClearlyBetter = (avgWifiSpeed >= avgMobileSpeed * hysteresisFactor) &&
                    (avgWifiLatency <= avgMobileLatency)

            if (isWifiClearlyBetter) {
                consecutiveBetterCount++
                if (consecutiveBetterCount >= thresholds.hysteresisChecksRequired) {
                    currentRecommendation = NetworkRecommendation.WIFI_IS_BETTER
                    evaluationReason = "Wi-Fi is better again: clearly superior for $consecutiveBetterCount checks (Wi-Fi: $summaryWifi vs Mobile: $summaryMobile)"
                    consecutiveBetterCount = 0
                    consecutiveWorseCount = 0
                } else {
                    evaluationReason = "Wi-Fi recovering (Hysteresis check $consecutiveBetterCount of ${thresholds.hysteresisChecksRequired} required to flip back)"
                }
            } else {
                // Wi-Fi is in the flapping zone or still worse -> Stay in MOBILE_IS_BETTER!
                consecutiveBetterCount = 0
                evaluationReason = "Mobile data is better: Wi-Fi not clearly superior yet (Hysteresis active; Wi-Fi: $summaryWifi vs Mobile: $summaryMobile)"
            }
        }

        return DecisionEvaluation(
            recommendation = currentRecommendation,
            reason = evaluationReason,
            avgWifiSpeedMbps = avgWifiSpeed,
            avgWifiLatencyMs = avgWifiLatency,
            avgMobileSpeedMbps = avgMobileSpeed,
            avgMobileLatencyMs = avgMobileLatency,
            consecutiveWorseCount = consecutiveWorseCount,
            consecutiveBetterCount = consecutiveBetterCount
        )
    }

    fun reset() {
        currentRecommendation = NetworkRecommendation.EVALUATING
        consecutiveWorseCount = 0
        consecutiveBetterCount = 0
    }
}
