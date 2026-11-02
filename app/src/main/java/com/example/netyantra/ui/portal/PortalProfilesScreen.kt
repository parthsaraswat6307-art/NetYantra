package com.example.netyantra.ui.portal

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.netyantra.portal.CaptivePortalManager
import com.example.netyantra.portal.PortalProfile
import com.example.netyantra.portal.PortalProfileRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalProfilesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { PortalProfileRepository.getInstance(context) }
    val portalManager = remember { CaptivePortalManager.getInstance(context) }

    val profiles by repository.profilesFlow.collectAsState()
    val activeWifiSsid by portalManager.currentWifiSsid.collectAsState()
    val statusMessage by portalManager.statusMessage.collectAsState()

    var editingProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var initialSsidForNew by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Captive Portal Profiles",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Keystore-backed auto-login credentials",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            initialSsidForNew = activeWifiSsid
                            isCreatingNew = true
                        }
                    ) {
                        Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    initialSsidForNew = activeWifiSsid
                    isCreatingNew = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
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
            // Active Wi-Fi Banner
            ActiveWifiStatusCard(
                activeSsid = activeWifiSsid,
                hasSavedProfile = repository.hasProfileForSsid(activeWifiSsid),
                statusMessage = statusMessage,
                onAddProfileForActiveWifi = {
                    initialSsidForNew = activeWifiSsid
                    isCreatingNew = true
                },
                onDeleteSavedLogin = {
                    portalManager.deleteSavedLoginForCurrentWifi()
                }
            )

            // Profiles List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Saved Login Profiles (${profiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (profiles.isEmpty()) {
                EmptyProfilesCard(
                    onAddProfile = {
                        initialSsidForNew = activeWifiSsid
                        isCreatingNew = true
                    }
                )
            } else {
                profiles.forEach { profile ->
                    val isActiveNetwork = activeWifiSsid?.equals(profile.ssid, ignoreCase = true) == true
                    ProfileItemCard(
                        profile = profile,
                        isActiveNetwork = isActiveNetwork,
                        onEdit = { editingProfile = profile },
                        onDeleteSavedLogin = { repository.deleteProfile(profile.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    // Dialog for Adding or Editing Profile
    if (isCreatingNew) {
        val detectedUrl = (portalManager.portalState.collectAsState().value as? com.example.netyantra.portal.PortalState.Detected)?.loginUrl ?: ""
        val defaultUrl = detectedUrl.ifBlank { portalManager.resolveGatewayLoginUrl() }

        ProfileEditDialog(
            initialProfile = PortalProfile(
                ssid = initialSsidForNew ?: activeWifiSsid ?: "",
                loginUrl = defaultUrl,
                usernameField = "username",
                passwordField = "password",
                username = "",
                password = ""
            ),
            isNew = true,
            onDismiss = { isCreatingNew = false },
            onSave = { newProfile ->
                repository.saveProfile(newProfile)
                isCreatingNew = false
            },
            onDeleteSavedLogin = {}
        )
    }

    editingProfile?.let { profile ->
        ProfileEditDialog(
            initialProfile = profile,
            isNew = false,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                repository.saveProfile(updated)
                editingProfile = null
            },
            onDeleteSavedLogin = {
                repository.deleteProfile(profile.id)
                editingProfile = null
            }
        )
    }
}

@Composable
private fun ActiveWifiStatusCard(
    activeSsid: String?,
    hasSavedProfile: Boolean,
    statusMessage: String?,
    onAddProfileForActiveWifi: () -> Unit,
    onDeleteSavedLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activeSsid != null) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Wi-Fi Network",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (activeSsid != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasSavedProfile) Color(0xFF4CAF50).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (hasSavedProfile) "Login Saved" else "No Saved Login",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasSavedProfile) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = activeSsid ?: "Not connected to Wi-Fi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (activeSsid != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasSavedProfile) {
                        OutlinedButton(
                            onClick = onDeleteSavedLogin,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Delete saved login", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onAddProfileForActiveWifi,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save login for this Wi-Fi", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileItemCard(
    profile: PortalProfile,
    isActiveNetwork: Boolean,
    onEdit: () -> Unit,
    onDeleteSavedLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        text = "📶",
                        fontSize = 18.sp
                    )
                    Text(
                        text = profile.ssid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isActiveNetwork) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Connected Now",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProfileDetailRow(label = "Portal URL", value = profile.loginUrl.ifBlank { "(Auto-detected from redirect)" })
                ProfileDetailRow(label = "Username", value = "${profile.username} (${profile.usernameField})")
                ProfileDetailRow(label = "Password", value = "•••••••• (${profile.passwordField}) - Keystore Encrypted")
                if (profile.extraFields.isNotEmpty()) {
                    ProfileDetailRow(
                        label = "Hidden Fields",
                        value = profile.extraFields.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDeleteSavedLogin,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete saved login")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onEdit,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyProfilesCard(
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "🔐",
                fontSize = 40.sp
            )
            Text(
                text = "No Captive Portal Profiles",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Save Wi-Fi captive portal credentials once. NetYantra will detect portals on connection and securely authenticate in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onAddProfile,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("+ Add Portal Profile", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProfileEditDialog(
    initialProfile: PortalProfile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (PortalProfile) -> Unit,
    onDeleteSavedLogin: () -> Unit
) {
    var ssid by remember { mutableStateOf(initialProfile.ssid) }
    var loginUrl by remember { mutableStateOf(initialProfile.loginUrl) }
    var usernameField by remember { mutableStateOf(initialProfile.usernameField) }
    var passwordField by remember { mutableStateOf(initialProfile.passwordField) }
    var username by remember { mutableStateOf(initialProfile.username) }
    var password by remember { mutableStateOf(initialProfile.password) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val extraFields = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(initialProfile.extraFields)
        }
    }

    var newExtraKey by remember { mutableStateOf("") }
    var newExtraValue by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isNew) "Add Captive Portal Profile" else "Edit Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Password is encrypted at rest using Android Keystore AES256-GCM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // SSID
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Wi-Fi SSID *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Portal Login URL
                OutlinedTextField(
                    value = loginUrl,
                    onValueChange = { loginUrl = it },
                    label = { Text("Portal Login URL (Optional)") },
                    placeholder = { Text("e.g. http://172.16.16.16:8090/httpclient.html") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Quick preset for Sophos / Cyberoam (Port 8090)
                OutlinedButton(
                    onClick = {
                        if (loginUrl.isBlank() || !loginUrl.contains(":8090")) {
                            loginUrl = "http://172.16.16.16:8090/httpclient.html"
                        }
                        usernameField = "username"
                        passwordField = "password"
                        extraFields["mode"] = "191"
                        extraFields["producttype"] = "0"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("⚡ Use Sophos / Cyberoam Preset (Port 8090)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (loginUrl.contains(":8090") || loginUrl.contains("httpclient", ignoreCase = true)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ℹ️ Sophos/Cyberoam firewall detected. NetYantra automatically ensures mode=191 and millisecond timestamps are sent.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Form Field Names
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = usernameField,
                        onValueChange = { usernameField = it },
                        label = { Text("User Field") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = passwordField,
                        onValueChange = { passwordField = it },
                        label = { Text("Password Field") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                // Username & Password Credentials
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password *") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(if (isPasswordVisible) "Hide" else "Show", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Extra Hidden Fields Section
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "Optional Extra / Hidden Fields",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                if (extraFields.isNotEmpty()) {
                    extraFields.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "$key = $value",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            IconButton(
                                onClick = { extraFields.remove(key) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Add Extra Field inputs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newExtraKey,
                        onValueChange = { newExtraKey = it },
                        placeholder = { Text("Key (e.g. magic)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newExtraValue,
                        onValueChange = { newExtraValue = it },
                        placeholder = { Text("Value", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newExtraKey.isNotBlank()) {
                                extraFields[newExtraKey.trim()] = newExtraValue.trim()
                                newExtraKey = ""
                                newExtraValue = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("+")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isNew) {
                        OutlinedButton(
                            onClick = onDeleteSavedLogin,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Delete saved login")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (ssid.isBlank()) {
                                errorMessage = "SSID cannot be empty"
                                return@Button
                            }
                            if (username.isBlank()) {
                                errorMessage = "Username cannot be empty"
                                return@Button
                            }
                            if (password.isBlank()) {
                                errorMessage = "Password cannot be empty"
                                return@Button
                            }

                            val profileToSave = initialProfile.copy(
                                ssid = ssid.trim(),
                                loginUrl = loginUrl.trim(),
                                usernameField = usernameField.ifBlank { "username" }.trim(),
                                passwordField = passwordField.ifBlank { "password" }.trim(),
                                username = username.trim(),
                                password = password,
                                extraFields = extraFields.toMap()
                            )
                            onSave(profileToSave)
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Profile")
                    }
                }
            }
        }
    }
}
