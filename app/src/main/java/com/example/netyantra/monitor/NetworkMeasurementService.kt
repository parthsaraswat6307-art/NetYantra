package com.example.netyantra.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.netyantra.MainActivity
import com.example.netyantra.data.AppSettings
import com.example.netyantra.data.MeasurementRepository
import com.example.netyantra.data.SettingsRepository
import com.example.netyantra.decision.DecisionThresholds
import com.example.netyantra.decision.NetworkDecisionEngine
import com.example.netyantra.decision.NetworkRecommendation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetworkMeasurementService : Service() {

    companion object {
        const val ACTION_START = "com.example.netyantra.action.START_MEASUREMENTS"
        const val ACTION_STOP = "com.example.netyantra.action.STOP_MEASUREMENTS"
        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"

        private const val CHANNEL_MEASUREMENTS_ID = "netyantra_measurements"
        private const val CHANNEL_RECOMMENDATIONS_ID = "netyantra_recommendations"
        private const val NOTIFICATION_ID = 1001
        private const val RECOMMENDATION_NOTIFICATION_ID = 2002
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var dualNetworkManager: DualNetworkManager
    private lateinit var settingsRepository: SettingsRepository
    private val networkTester = NetworkTester()
    private val decisionEngine = NetworkDecisionEngine()

    private var measurementLoopJob: Job? = null
    private var settingsCollectorJob: Job? = null

    private var currentSettings = AppSettings()
    private var previousRecommendation = NetworkRecommendation.EVALUATING

    override fun onCreate() {
        super.onCreate()
        dualNetworkManager = DualNetworkManager(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        createNotificationChannels()
        observeSettings()
    }

    private fun observeSettings() {
        settingsCollectorJob?.cancel()
        settingsCollectorJob = serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                decisionEngine.thresholds = DecisionThresholds(
                    speedThresholdPercent = settings.speedThresholdPercent
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMeasurementService()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                serviceScope.launch {
                    val settings = settingsRepository.settingsFlow.first()
                    currentSettings = settings
                    val requestedInterval = intent?.getLongExtra(EXTRA_INTERVAL_SECONDS, -1L) ?: -1L
                    val activeInterval = if (requestedInterval > 0) requestedInterval else settings.checkIntervalSeconds

                    startForegroundServiceWithNotification()
                    startMonitoring(activeInterval)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val initialNotification = buildMeasurementNotification("Initializing network measurements...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        MeasurementRepository.setServiceRunning(true)
    }

    private fun startMonitoring(intervalSec: Long) {
        dualNetworkManager.startListening()

        measurementLoopJob?.cancel()
        measurementLoopJob = serviceScope.launch {
            delay(1000L) // Initial stabilization delay

            while (isActive) {
                performMeasurementCycle()
                val waitTime = maxOf(5L, currentSettings.checkIntervalSeconds)
                delay(waitTime * 1000L)
            }
        }
    }

    private suspend fun performMeasurementCycle() {
        MeasurementRepository.setTestingState(isWifiTesting = true, isCellularTesting = true)

        coroutineScope {
            // Wi-Fi Test
            val wifiJob = async {
                val wifiNetwork = dualNetworkManager.wifiNetwork
                if (wifiNetwork != null) {
                    val result = networkTester.executeMeasurement(wifiNetwork)
                    MeasurementRepository.recordWifiResult(result)
                } else {
                    MeasurementRepository.recordWifiResult(
                        NetworkTestResult(
                            isSuccess = false,
                            errorMessage = "Wi-Fi disconnected"
                        )
                    )
                }
            }

            // Mobile Data Test (Protected by Monthly Limit)
            val cellularJob = async {
                val cellNetwork = dualNetworkManager.cellularNetwork
                if (cellNetwork == null) {
                    MeasurementRepository.recordCellularResult(
                        NetworkTestResult(
                            isSuccess = false,
                            errorMessage = "Mobile data unavailable"
                        )
                    )
                } else if (currentSettings.isMonthlyLimitExceeded) {
                    // Mobile monthly data quota reached - skip to preserve user bandwidth
                    MeasurementRepository.recordCellularResult(
                        NetworkTestResult(
                            isSuccess = false,
                            errorMessage = "Monthly limit reached (${currentSettings.monthlyMobileDataLimitMb} MB)"
                        )
                    )
                } else {
                    val result = networkTester.executeMeasurement(cellNetwork)
                    MeasurementRepository.recordCellularResult(result)
                    if (result.bytesDownloaded > 0) {
                        settingsRepository.recordMobileUsage(result.bytesDownloaded)
                    }
                }
            }

            awaitAll(wifiJob, cellularJob)
        }

        MeasurementRepository.setTestingState(isWifiTesting = false, isCellularTesting = false)

        // Evaluate Decision Engine
        val snapshot = MeasurementRepository.state.value
        val evaluation = decisionEngine.evaluate(
            wifiHistory = snapshot.wifiMetrics.history,
            mobileHistory = snapshot.cellularMetrics.history
        )
        MeasurementRepository.updateDecisionEvaluation(evaluation)

        // Trigger notification if newly recommended Mobile Data
        if (evaluation.recommendation == NetworkRecommendation.MOBILE_IS_BETTER) {
            if (previousRecommendation != NetworkRecommendation.MOBILE_IS_BETTER && currentSettings.notificationsEnabled) {
                showRecommendationNotification(evaluation.reason)
            }
        }
        previousRecommendation = evaluation.recommendation

        updateOngoingNotification()
    }

    private fun updateOngoingNotification() {
        val currentState = MeasurementRepository.state.value
        val wifi = currentState.wifiMetrics.latestResult
        val cell = currentState.cellularMetrics.latestResult

        val wifiText = if (currentState.wifiMetrics.isAvailable && wifi != null && wifi.isSuccess) {
            "Wi-Fi: ${wifi.latencyMs}ms, ${String.format("%.1f", wifi.speedMbps ?: 0.0)}Mbps"
        } else if (currentState.wifiMetrics.isAvailable) {
            "Wi-Fi: Connected"
        } else {
            "Wi-Fi: Offline"
        }

        val cellText = if (currentState.cellularMetrics.isAvailable && cell != null && cell.isSuccess) {
            "Mobile: ${cell.latencyMs}ms, ${String.format("%.1f", cell.speedMbps ?: 0.0)}Mbps"
        } else if (currentState.cellularMetrics.isAvailable) {
            "Mobile: Connected"
        } else {
            "Mobile: Offline"
        }

        val statusText = "${currentState.decisionEvaluation.recommendation.displayName} | $wifiText | $cellText"
        val notification = buildMeasurementNotification(statusText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildMeasurementNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NetworkMeasurementService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_MEASUREMENTS_ID)
            .setContentTitle("NetYantra Network Monitor")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showRecommendationNotification(reason: String) {
        // Build settings intent to open system connectivity panel
        val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val settingsPendingIntent = PendingIntent.getActivity(
            this,
            2002,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2003,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_RECOMMENDATIONS_ID)
            .setContentTitle("Mobile data is better")
            .setContentText("Wi-Fi underperformed for 3 checks. Tap to switch to Mobile Data.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Open Network Settings",
                settingsPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(RECOMMENDATION_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val measurementsChannel = NotificationChannel(
                CHANNEL_MEASUREMENTS_ID,
                "Network Measurements",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing network latency and download speed measurements"
            }

            val recommendationsChannel = NotificationChannel(
                CHANNEL_RECOMMENDATIONS_ID,
                "Network Recommendations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when mobile data significantly outperforms Wi-Fi"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(measurementsChannel)
            manager?.createNotificationChannel(recommendationsChannel)
        }
    }

    private fun stopMeasurementService() {
        measurementLoopJob?.cancel()
        settingsCollectorJob?.cancel()
        dualNetworkManager.stopListening()
        MeasurementRepository.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        measurementLoopJob?.cancel()
        settingsCollectorJob?.cancel()
        serviceScope.cancel()
        dualNetworkManager.stopListening()
        MeasurementRepository.setServiceRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
