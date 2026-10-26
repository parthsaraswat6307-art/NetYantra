package com.example.netyantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.netyantra.portal.CaptivePortalManager
import com.example.netyantra.ui.home.HomeScreen
import com.example.netyantra.ui.portal.PortalProfilesScreen
import com.example.netyantra.ui.settings.SettingsScreen
import com.example.netyantra.ui.theme.NetYantraTheme

enum class CurrentScreen {
    HOME,
    SETTINGS,
    PORTAL_PROFILES
}

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            CaptivePortalManager.getInstance(applicationContext).resolveWifiSsid()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        setContent {
            NetYantraTheme {
                var currentScreen by remember { mutableStateOf(CurrentScreen.HOME) }

                BackHandler(enabled = currentScreen != CurrentScreen.HOME) {
                    currentScreen = CurrentScreen.HOME
                }

                when (currentScreen) {
                    CurrentScreen.HOME -> {
                        HomeScreen(
                            onOpenSettings = { currentScreen = CurrentScreen.SETTINGS },
                            onOpenPortalProfiles = { currentScreen = CurrentScreen.PORTAL_PROFILES }
                        )
                    }
                    CurrentScreen.SETTINGS -> {
                        SettingsScreen(
                            onNavigateBack = { currentScreen = CurrentScreen.HOME },
                            onOpenPortalProfiles = { currentScreen = CurrentScreen.PORTAL_PROFILES }
                        )
                    }
                    CurrentScreen.PORTAL_PROFILES -> {
                        PortalProfilesScreen(
                            onNavigateBack = { currentScreen = CurrentScreen.HOME }
                        )
                    }
                }
            }
        }
    }
}