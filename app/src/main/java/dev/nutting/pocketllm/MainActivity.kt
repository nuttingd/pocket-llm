package dev.nutting.pocketllm

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A conversation to open, from a notification tap; cleared once the nav graph has shown it. */
    private val openConversationId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch: after a configuration change the conversation is already open
        if (savedInstanceState == null) openConversationId.value = intent.getStringExtra(EXTRA_CONVERSATION_ID)
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
                val conversationToOpen by openConversationId.collectAsState()
                AppNavGraph(
                    navController = navController,
                    openConversationId = conversationToOpen,
                    onConversationOpened = { openConversationId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { openConversationId.value = it }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "dev.nutting.pocketllm.extra.CONVERSATION_ID"
    }
}
