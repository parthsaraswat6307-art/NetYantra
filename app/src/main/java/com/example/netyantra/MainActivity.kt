package com.example.netyantra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.netyantra.ui.home.HomeScreen
import com.example.netyantra.ui.theme.NetYantraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetYantraTheme {
                HomeScreen()
            }
        }
    }
}