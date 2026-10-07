package com.nousresearch.hermes.jr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nousresearch.hermes.jr.ui.HermesJrApp
import com.nousresearch.hermes.jr.ui.theme.HermesJrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0D1117.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF0D1117.toInt()),
        )
        setContent {
            HermesJrTheme {
                HermesJrApp()
            }
        }
    }
}
