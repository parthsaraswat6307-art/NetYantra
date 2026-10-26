package com.example.netyantra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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