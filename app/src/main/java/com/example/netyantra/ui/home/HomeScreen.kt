package com.example.netyantra.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.netyantra.data.MeasurementRepository
import com.example.netyantra.decision.DecisionEvaluation
import com.example.netyantra.decision.NetworkRecommendation
import com.example.netyantra.monitor.NetworkMetrics
import com.example.netyantra.monitor.NetworkTestResult
import com.example.netyantra.monitor.TransportType
import com.example.netyantra.ui.theme.NetYantraTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val measurementState by MeasurementRepository.state.collectAsState()

    var selectedInterval by remember { mutableStateOf(measurementState.intervalSeconds) }

    // Android 13+ Notification Permission Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Start service once permission is responded to
        MeasurementRepository.startService(context, selectedInterval)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NetYantra",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Dual Network Monitor (30s cycle)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙", fontSize = 22.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Controls Card
            ServiceControlCard(
                isRunning = measurementState.isServiceRunning,
                currentInterval = selectedInterval,
                onIntervalSelected = { newInterval ->
                    selectedInterval = newInterval
                    MeasurementRepository.setIntervalSeconds(newInterval)
                    if (measurementState.isServiceRunning) {
                        MeasurementRepository.startService(context, newInterval)
                    }
                },
                onToggleService = {
                    if (measurementState.isServiceRunning) {
                        MeasurementRepository.stopService(context)
                    } else {
                        // Check POST_NOTIFICATIONS permission on Android 13+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            MeasurementRepository.startService(context, selectedInterval)
                        }
                    }
                }
            )

            // Decision Engine Status Banner (When Mobile is Better)
            val decision = measurementState.decisionEvaluation
            if (decision.recommendation == NetworkRecommendation.MOBILE_IS_BETTER) {
                MobileDataBetterAlertCard(decision = decision)
            }

            // Wi-Fi Live Section
            LiveNetworkSectionCard(
                title = "Wi-Fi",
                transportType = TransportType.WIFI,
                metrics = measurementState.wifiMetrics,
                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                badgeTextColor = MaterialTheme.colorScheme.onPrimaryContainer
            )

            // Mobile Data Live Section
            LiveNetworkSectionCard(
                title = "Mobile data",
                transportType = TransportType.CELLULAR,
                metrics = measurementState.cellularMetrics,
                badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                badgeTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                subtext = "Low test usage mode (~200 KB per sample)"
            )

            // Overall Status Section with Decision Engine Summary
            StatusSummaryCard(
                isServiceRunning = measurementState.isServiceRunning,
                intervalSeconds = measurementState.intervalSeconds,
                wifiAvailable = measurementState.wifiMetrics.isAvailable,
                cellularAvailable = measurementState.cellularMetrics.isAvailable,
                decision = decision,
                lastRunTimestamp = measurementState.lastRunTimestamp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MobileDataBetterAlertCard(
    decision: DecisionEvaluation,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ Mobile data is better",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = decision.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Open Network Settings Panel", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ServiceControlCard(
    isRunning: Boolean,
    currentInterval: Long,
    onIntervalSelected: (Long) -> Unit,
    onToggleService: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Measurement Service",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isRunning) "Running via Foreground Service" else "Service Stopped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val indicatorColor by animateColorAsState(
                    targetValue = if (isRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    label = "statusIndicator"
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = indicatorColor.copy(alpha = 0.15f),
                    modifier = Modifier.border(1.dp, indicatorColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRunning) "ACTIVE" else "IDLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = indicatorColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interval Configuration Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Interval:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(15L, 30L, 60L).forEach { seconds ->
                    FilterChip(
                        selected = currentInterval == seconds,
                        onClick = { onIntervalSelected(seconds) },
                        label = { Text("${seconds}s") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Start / Stop Button
            if (isRunning) {
                OutlinedButton(
                    onClick = onToggleService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop Monitoring", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onToggleService,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start Monitoring (Wi-Fi + Mobile)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LiveNetworkSectionCard(
    title: String,
    transportType: TransportType,
    metrics: NetworkMetrics,
    badgeColor: Color,
    badgeTextColor: Color,
    subtext: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusDotColor = if (metrics.isAvailable) Color(0xFF4CAF50) else Color(0xFFE53935)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (subtext != null) {
                        Text(
                            text = subtext,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (metrics.isAvailable) badgeColor else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (metrics.isAvailable) "Connected" else "Unavailable",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (metrics.isAvailable) badgeTextColor else MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Measurement in-progress spinner
            AnimatedVisibility(visible = metrics.isTesting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Running socket measurement...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            // Latest Result Display
            val latest = metrics.latestResult
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = "Latency (TTFB)",
                    value = latest?.latencyMs?.let { "${it} ms" } ?: "--"
                )
                MetricColumn(
                    label = "Download Speed",
                    value = latest?.speedMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "--"
                )
                MetricColumn(
                    label = "Last Sample",
                    value = latest?.formattedTime ?: "--"
                )
            }

            if (latest != null && !latest.isSuccess && latest.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Last error: ${latest.errorMessage}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }
            }

            // History Section (Last 10 results)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Rolling History (Last ${metrics.history.size}/10)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (metrics.history.isEmpty()) {
                Text(
                    text = "No samples collected yet. Start the service to record live data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    metrics.history.reversed().forEachIndexed { index, result ->
                        HistoryRow(index = index + 1, result = result)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HistoryRow(index: Int, result: NetworkTestResult) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$index  ${result.formattedTime}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )

            if (result.isSuccess) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${result.latencyMs ?: "--"} ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f Mbps", result.speedMbps ?: 0.0),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            } else {
                Text(
                    text = result.errorMessage?.take(20) ?: "Failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    isServiceRunning: Boolean,
    intervalSeconds: Long,
    wifiAvailable: Boolean,
    cellularAvailable: Boolean,
    decision: DecisionEvaluation,
    lastRunTimestamp: Long?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Decision & Network Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow("Engine Recommendation", decision.recommendation.displayName)
            StatusRow("Decision Details", decision.reason)
            StatusRow("Service State", if (isServiceRunning) "Foreground Service Active" else "Stopped")
            StatusRow("Measurement Cadence", "Every ${intervalSeconds}s")
            StatusRow("Consecutive Worse Checks", "${decision.consecutiveWorseCount} of 3")
            StatusRow("Anti-Flapping Hysteresis", if (decision.consecutiveBetterCount > 0) "Recovery: ${decision.consecutiveBetterCount}/2" else "Active")
            StatusRow("Active Interfaces", "Wi-Fi: ${if (wifiAvailable) "Available" else "None"} | Mobile: ${if (cellularAvailable) "Available" else "None"}")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    NetYantraTheme {
        HomeScreen()
    }
}
