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
import com.example.netyantra.ui.settings.SettingsScreen
import com.example.netyantra.ui.theme.NetYantraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetYantraTheme {
                var isSettingsOpen by remember { mutableStateOf(false) }

                BackHandler(enabled = isSettingsOpen) {
                    isSettingsOpen = false
                }

                if (isSettingsOpen) {
                    SettingsScreen(onNavigateBack = { isSettingsOpen = false })
                } else {
                    HomeScreen(onOpenSettings = { isSettingsOpen = true })
                }
            }
        }
    }
}