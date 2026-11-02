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
import com.example.netyantra.portal.SophosPortalScreen
import com.example.netyantra.ui.home.HomeScreen
import com.example.netyantra.ui.settings.SettingsScreen
import com.example.netyantra.ui.theme.NetYantraTheme

enum class AppScreen {
    HOME,
    SETTINGS,
    PORTAL
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetYantraTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

                BackHandler(enabled = currentScreen != AppScreen.HOME) {
                    currentScreen = AppScreen.HOME
                }

                when (currentScreen) {
                    AppScreen.SETTINGS -> SettingsScreen(onNavigateBack = { currentScreen = AppScreen.HOME })
                    AppScreen.PORTAL -> SophosPortalScreen(onNavigateBack = { currentScreen = AppScreen.HOME })
                    AppScreen.HOME -> HomeScreen(
                        onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                        onOpenPortal = { currentScreen = AppScreen.PORTAL }
                    )
                }
            }
        }
    }
}