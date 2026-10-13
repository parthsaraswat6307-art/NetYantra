package com.example.netyantra.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.netyantra.monitor.MeasurementConfig
import com.example.netyantra.monitor.MeasurementState
import com.example.netyantra.monitor.NetworkMeasurementService
import com.example.netyantra.monitor.NetworkTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object MeasurementRepository {

    private val _state = MutableStateFlow(MeasurementState())
    val state: StateFlow<MeasurementState> = _state.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _state.update { current ->
            current.copy(
                isServiceRunning = running,
                activeSummary = if (running) "Running every ${current.intervalSeconds}s" else "Monitoring Stopped"
            )
        }
    }

    fun setIntervalSeconds(seconds: Long) {
        _state.update { current ->
            current.copy(
                intervalSeconds = seconds,
                activeSummary = if (current.isServiceRunning) "Running every ${seconds}s" else current.activeSummary
            )
        }
    }

    fun updateWifiAvailability(isAvailable: Boolean) {
        _state.update { current ->
            current.copy(
                wifiMetrics = current.wifiMetrics.copy(isAvailable = isAvailable)
            )
        }
    }

    fun updateCellularAvailability(isAvailable: Boolean) {
        _state.update { current ->
            current.copy(
                cellularMetrics = current.cellularMetrics.copy(isAvailable = isAvailable)
            )
        }
    }

    fun setTestingState(isWifiTesting: Boolean, isCellularTesting: Boolean) {
        _state.update { current ->
            current.copy(
                wifiMetrics = current.wifiMetrics.copy(isTesting = isWifiTesting),
                cellularMetrics = current.cellularMetrics.copy(isTesting = isCellularTesting)
            )
        }
    }

    fun recordWifiResult(result: NetworkTestResult) {
        _state.update { current ->
            current.copy(
                wifiMetrics = current.wifiMetrics.withNewResult(result),
                lastRunTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun recordCellularResult(result: NetworkTestResult) {
        _state.update { current ->
            current.copy(
                cellularMetrics = current.cellularMetrics.withNewResult(result),
                lastRunTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun updateDecisionEvaluation(evaluation: com.example.netyantra.decision.DecisionEvaluation) {
        _state.update { current ->
            current.copy(decisionEvaluation = evaluation)
        }
    }

    fun startService(context: Context, intervalSeconds: Long = _state.value.intervalSeconds) {
        setIntervalSeconds(intervalSeconds)
        val intent = Intent(context, NetworkMeasurementService::class.java).apply {
            action = NetworkMeasurementService.ACTION_START
            putExtra(NetworkMeasurementService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, NetworkMeasurementService::class.java).apply {
            action = NetworkMeasurementService.ACTION_STOP
        }
        context.startService(intent)
    }
}
