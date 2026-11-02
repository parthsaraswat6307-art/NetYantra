package com.example.netyantra.ui.portal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netyantra.portal.CaptivePortalManager
import com.example.netyantra.portal.PortalProfileRepository
import com.example.netyantra.portal.PortalState
import kotlinx.coroutines.launch

@Composable
fun CaptivePortalCard(
    onOpenPortalProfiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { CaptivePortalManager.getInstance(context) }
    val repository = remember { PortalProfileRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    val portalState by manager.portalState.collectAsState()
    val activeSsid by manager.currentWifiSsid.collectAsState()
    val isAutoLoggingIn by manager.isAutoLoggingIn.collectAsState()
    val statusMessage by manager.statusMessage.collectAsState()
    val isWifiConnected by manager.isWifiConnected.collectAsState()
    val profiles by repository.profilesFlow.collectAsState()

    val currentSsid = activeSsid ?: ""
    val detectedUrl = (portalState as? PortalState.Detected)?.loginUrl
    val matchedProfile = repository.getProfileForSsid(currentSsid, detectedUrl)
    val hasSavedLogin = matchedProfile != null

    val isDetected = portalState is PortalState.Detected
    val isChecking = portalState is PortalState.Checking
    val isLoggingIn = portalState is PortalState.LoggingIn || isAutoLoggingIn
    val isAuth = portalState is PortalState.Authenticated
    val isError = portalState is PortalState.Error

    val cardColor = when {
        isDetected || isLoggingIn -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        isAuth -> Color(0xFF4CAF50).copy(alpha = 0.12f)
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            isDetected || isLoggingIn -> "⚠️"
                            isAuth -> "🛡️"
                            isChecking -> "⏳"
                            else -> "🔐"
                        },
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Captive Portal Auto-Login",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isLoggingIn -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        isDetected -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        isAuth -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    }
                ) {
                    Text(
                        text = when {
                            isLoggingIn -> "Authenticating"
                            isDetected -> "Portal Detected"
                            isAuth -> "Authenticated"
                            isChecking -> "Checking"
                            isError -> "Error"
                            else -> "${profiles.size} saved"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isLoggingIn -> MaterialTheme.colorScheme.primary
                            isDetected -> MaterialTheme.colorScheme.error
                            isAuth -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Description / Status text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        !isWifiConnected -> "Wi-Fi: Not connected"
                        currentSsid.isNotBlank() -> "Wi-Fi: $currentSsid"
                        matchedProfile != null -> "Wi-Fi: ${matchedProfile.ssid} (Saved)"
                        else -> "Wi-Fi: Connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (hasSavedLogin && isWifiConnected) {
                    Text(
                        text = "Login Profile Active",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold
                    )
                } else if (hasSavedLogin) {
                    Text(
                        text = "Profile Saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isWifiConnected || statusMessage?.contains("Wi-Fi not connected", ignoreCase = true) == true) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "💡 Make sure your phone is connected to the college Wi-Fi in Android Settings. If your phone automatically switches back to Mobile Data, tap 'Keep Wi-Fi connection' or temporarily turn off Mobile Data while logging in.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (portalState is PortalState.Detected) {
                val detected = portalState as PortalState.Detected
                if (detected.loginUrl.isNotBlank()) {
                    Text(
                        text = "Login URL: ${detected.loginUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (portalState is PortalState.Error) {
                Text(
                    text = (portalState as PortalState.Error).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // If a login profile is saved, provide "Delete saved login"
                if (hasSavedLogin) {
                    OutlinedButton(
                        onClick = { manager.deleteSavedLoginForCurrentWifi() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete saved login", fontSize = 12.sp)
                    }
                }

                // Auto-login trigger or check trigger
                if (hasSavedLogin) {
                    Button(
                        onClick = {
                            scope.launch {
                                manager.performAutoLoginForCurrentWifi()
                            }
                        },
                        enabled = !isLoggingIn,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Auto-Login Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isDetected || isError) {
                    Button(
                        onClick = onOpenPortalProfiles,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Login Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                manager.checkAndAutoLogin()
                            }
                        },
                        enabled = !isChecking && !isLoggingIn,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isChecking) "Checking..." else "Check Portal", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = onOpenPortalProfiles,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Profiles", fontSize = 12.sp)
                }
            }
        }
    }
}
