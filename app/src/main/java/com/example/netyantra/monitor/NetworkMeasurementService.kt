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
import androidx.core.app.NotificationCompat
import com.example.netyantra.MainActivity
import com.example.netyantra.data.MeasurementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetworkMeasurementService : Service() {

    companion object {
        const val ACTION_START = "com.example.netyantra.action.START_MEASUREMENTS"
        const val ACTION_STOP = "com.example.netyantra.action.STOP_MEASUREMENTS"
        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"

        private const val CHANNEL_ID = "netyantra_measurements"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var dualNetworkManager: DualNetworkManager
    private val networkTester = NetworkTester()
    private var measurementLoopJob: Job? = null
    private var intervalSeconds = MeasurementConfig.DEFAULT_INTERVAL_SECONDS

    override fun onCreate() {
        super.onCreate()
        dualNetworkManager = DualNetworkManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMeasurementService()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val newInterval = intent?.getLongExtra(
                    EXTRA_INTERVAL_SECONDS,
                    MeasurementConfig.DEFAULT_INTERVAL_SECONDS
                ) ?: MeasurementConfig.DEFAULT_INTERVAL_SECONDS
                intervalSeconds = maxOf(5L, newInterval)

                startForegroundServiceWithNotification()
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val initialNotification = buildNotification("Initializing network measurements...")

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

    private fun startMonitoring() {
        dualNetworkManager.startListening()

        measurementLoopJob?.cancel()
        measurementLoopJob = serviceScope.launch {
            // Small initial delay so network callbacks have a moment to register
            delay(1000L)

            while (isActive) {
                performMeasurementCycle()
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private suspend fun performMeasurementCycle() {
        MeasurementRepository.setTestingState(isWifiTesting = true, isCellularTesting = true)

        coroutineScope {
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

            val cellularJob = async {
                val cellNetwork = dualNetworkManager.cellularNetwork
                if (cellNetwork != null) {
                    val result = networkTester.executeMeasurement(cellNetwork)
                    MeasurementRepository.recordCellularResult(result)
                } else {
                    MeasurementRepository.recordCellularResult(
                        NetworkTestResult(
                            isSuccess = false,
                            errorMessage = "Mobile data unavailable"
                        )
                    )
                }
            }

            awaitAll(wifiJob, cellularJob)
        }

        MeasurementRepository.setTestingState(isWifiTesting = false, isCellularTesting = false)
        updateNotification()
    }

    private fun updateNotification() {
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

        val summary = "$wifiText | $cellText"
        val notification = buildNotification(summary)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetYantra Network Monitor")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Network Measurements",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing network latency and download speed measurements"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopMeasurementService() {
        measurementLoopJob?.cancel()
        dualNetworkManager.stopListening()
        MeasurementRepository.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        measurementLoopJob?.cancel()
        serviceScope.cancel()
        dualNetworkManager.stopListening()
        MeasurementRepository.setServiceRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
