package com.example.netyantra.decision

import com.example.netyantra.monitor.NetworkTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkDecisionEngineTest {

    private lateinit var engine: NetworkDecisionEngine

    @Before
    fun setUp() {
        engine = NetworkDecisionEngine(
            DecisionThresholds(
                speedThresholdPercent = 30,     // 30% lower speed
                latencyMultiplier = 2.0,       // 2x higher latency
                consecutiveWorseRequired = 3,  // 3 checks in a row
                hysteresisSpeedPercent = 10,   // Wi-Fi must be >= 110% of Mobile speed to flip back
                hysteresisChecksRequired = 2   // 2 checks in a row to flip back
            )
        )
    }

    private fun sample(speedMbps: Double, latencyMs: Long): NetworkTestResult {
        return NetworkTestResult(
            isSuccess = true,
            downloadSpeedKbps = speedMbps * 1000.0,
            latencyMs = latencyMs,
            bytesDownloaded = 200000,
            durationMs = 1000
        )
    }

    @Test
    fun initialState_startsInEvaluating_withFewerThan3Samples() {
        val wifi = listOf(sample(speedMbps = 20.0, latencyMs = 25))
        val mobile = listOf(sample(speedMbps = 15.0, latencyMs = 30))

        val result = engine.evaluate(wifi, mobile)

        assertEquals(NetworkRecommendation.EVALUATING, result.recommendation)
        assertTrue(result.reason.contains("Need 3 samples"))
    }

    @Test
    fun wifiBetter_transitionsToWifiIsBetter() {
        // Wi-Fi 30 Mbps / 20 ms vs Mobile 10 Mbps / 40 ms
        val wifi = listOf(
            sample(30.0, 20),
            sample(32.0, 22),
            sample(28.0, 18)
        )
        val mobile = listOf(
            sample(10.0, 40),
            sample(12.0, 42),
            sample(11.0, 38)
        )

        val result = engine.evaluate(wifi, mobile)

        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, result.recommendation)
        assertEquals(0, result.consecutiveWorseCount)
    }

    @Test
    fun wifiWorseSpeed_requires3ConsecutiveChecksBeforeSwitchingToMobile() {
        // Mobile speed = 20 Mbps. Wi-Fi speed = 10 Mbps (50% drop, > 30% threshold)
        val mobile = listOf(sample(20.0, 30), sample(20.0, 30), sample(20.0, 30))
        val badWifi = listOf(sample(10.0, 30), sample(10.0, 30), sample(10.0, 30))

        // Check 1: Wi-Fi worse, count = 1
        val check1 = engine.evaluate(badWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check1.recommendation)
        assertEquals(1, check1.consecutiveWorseCount)

        // Check 2: Wi-Fi worse, count = 2
        val check2 = engine.evaluate(badWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check2.recommendation)
        assertEquals(2, check2.consecutiveWorseCount)

        // Check 3: Wi-Fi worse for 3 in a row -> Switches to MOBILE_IS_BETTER
        val check3 = engine.evaluate(badWifi, mobile)
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, check3.recommendation)
        assertEquals(3, check3.consecutiveWorseCount)
        assertTrue(check3.reason.contains("Mobile data is better"))
    }

    @Test
    fun wifiWorseLatency_requires3ConsecutiveChecksBeforeSwitchingToMobile() {
        // Mobile latency = 30 ms. Wi-Fi latency = 70 ms (> 2x higher)
        // Speeds are equal
        val mobile = listOf(sample(15.0, 30), sample(15.0, 30), sample(15.0, 30))
        val laggyWifi = listOf(sample(15.0, 70), sample(15.0, 70), sample(15.0, 70))

        engine.evaluate(laggyWifi, mobile)
        assertEquals(1, engine.consecutiveWorseCount)

        engine.evaluate(laggyWifi, mobile)
        assertEquals(2, engine.consecutiveWorseCount)

        val check3 = engine.evaluate(laggyWifi, mobile)
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, check3.recommendation)
        assertEquals(3, engine.consecutiveWorseCount)
    }

    @Test
    fun intermittentSpikes_flappingPrevention_doesNotSwitchIfInterrupted() {
        val mobile = listOf(sample(20.0, 30), sample(20.0, 30), sample(20.0, 30))
        val badWifi = listOf(sample(10.0, 30), sample(10.0, 30), sample(10.0, 30))
        val goodWifi = listOf(sample(22.0, 25), sample(22.0, 25), sample(22.0, 25))

        // Check 1: Bad
        engine.evaluate(badWifi, mobile)
        assertEquals(1, engine.consecutiveWorseCount)

        // Check 2: Bad
        engine.evaluate(badWifi, mobile)
        assertEquals(2, engine.consecutiveWorseCount)

        // Check 3: Interrupted by Good sample! Count resets to 0
        val check3 = engine.evaluate(goodWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check3.recommendation)
        assertEquals(0, engine.consecutiveWorseCount)

        // Check 4: Bad again -> Count starts over at 1
        val check4 = engine.evaluate(badWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check4.recommendation)
        assertEquals(1, engine.consecutiveWorseCount)
    }

    @Test
    fun hysteresis_flappingZone_doesNotFlipBackToWifiOnMarginalPerformance() {
        val mobile = listOf(sample(20.0, 30), sample(20.0, 30), sample(20.0, 30))
        val badWifi = listOf(sample(10.0, 30), sample(10.0, 30), sample(10.0, 30))

        // Force state into MOBILE_IS_BETTER (3 bad checks)
        repeat(3) { engine.evaluate(badWifi, mobile) }
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, engine.currentRecommendation)

        // Wi-Fi marginally recovers to 20.5 Mbps (equal to mobile, but NOT > 110% hysteresis threshold)
        val marginalWifi = listOf(sample(20.5, 30), sample(20.5, 30), sample(20.5, 30))

        val hysteresisCheck1 = engine.evaluate(marginalWifi, mobile)
        // Must stay on MOBILE_IS_BETTER to prevent flapping!
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, hysteresisCheck1.recommendation)
        assertEquals(0, engine.consecutiveBetterCount)

        val hysteresisCheck2 = engine.evaluate(marginalWifi, mobile)
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, hysteresisCheck2.recommendation)
    }

    @Test
    fun hysteresis_flipsBackToWifi_whenClearlyBetterForRequiredChecks() {
        val mobile = listOf(sample(20.0, 30), sample(20.0, 30), sample(20.0, 30))
        val badWifi = listOf(sample(10.0, 30), sample(10.0, 30), sample(10.0, 30))

        // 1. Enter MOBILE_IS_BETTER
        repeat(3) { engine.evaluate(badWifi, mobile) }
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, engine.currentRecommendation)

        // 2. Wi-Fi becomes CLEARLY better: 30 Mbps (> 20 * 1.1 = 22 Mbps) and 20 ms (< 30 ms)
        val greatWifi = listOf(sample(30.0, 20), sample(30.0, 20), sample(30.0, 20))

        // Hysteresis Check 1: 1 of 2 required checks
        val check1 = engine.evaluate(greatWifi, mobile)
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, check1.recommendation)
        assertEquals(1, engine.consecutiveBetterCount)

        // Hysteresis Check 2: 2 of 2 required checks -> Successfully flips back to WIFI_IS_BETTER!
        val check2 = engine.evaluate(greatWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check2.recommendation)
        assertEquals(0, engine.consecutiveBetterCount)
        assertTrue(check2.reason.contains("Wi-Fi is better again"))
    }

    @Test
    fun customThresholds_respectsCustomSettings() {
        val customEngine = NetworkDecisionEngine(
            DecisionThresholds(
                speedThresholdPercent = 50,    // 50% threshold
                latencyMultiplier = 3.0,      // 3x latency
                consecutiveWorseRequired = 2   // Only 2 checks needed
            )
        )

        val mobile = listOf(sample(20.0, 20), sample(20.0, 20), sample(20.0, 20))
        // Wi-Fi speed = 12 Mbps (40% drop: worse under 30% threshold, but NOT worse under 50% threshold)
        val moderateWifi = listOf(sample(12.0, 20), sample(12.0, 20), sample(12.0, 20))

        val check1 = customEngine.evaluate(moderateWifi, mobile)
        assertEquals(NetworkRecommendation.WIFI_IS_BETTER, check1.recommendation)
        assertEquals(0, customEngine.consecutiveWorseCount)

        // Now Wi-Fi speed = 8 Mbps (60% drop: worse under 50% threshold)
        val severeWifi = listOf(sample(8.0, 20), sample(8.0, 20), sample(8.0, 20))
        customEngine.evaluate(severeWifi, mobile)
        assertEquals(1, customEngine.consecutiveWorseCount)

        val check3 = customEngine.evaluate(severeWifi, mobile)
        // With consecutiveWorseRequired = 2, it switches on check 2!
        assertEquals(NetworkRecommendation.MOBILE_IS_BETTER, check3.recommendation)
    }
}
