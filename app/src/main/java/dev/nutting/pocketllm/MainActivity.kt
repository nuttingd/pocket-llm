package dev.nutting.pocketllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import dev.nutting.pocketllm.ui.navigation.AppNavGraph
import dev.nutting.pocketllm.ui.theme.PocketLlmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = remember { (application as PocketLlmApplication).container }
            val themeMode by container.settingsRepository.getThemeMode().collectAsState(initial = "system")
            val dynamicColor by container.settingsRepository.getDynamicColorEnabled().collectAsState(initial = true)

            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            PocketLlmTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
            ) {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }
    }
}
