package org.aethra.assistant.aethra.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.aethra.assistant.AthraHomePage
import org.aethra.assistant.aethra.ui.DeviceScreen
import org.aethra.assistant.aethra.ui.SettingsScreen
import org.aethra.assistant.aethra.ui.VisualModeState
import org.aethra.assistant.aethra.ui.VoiceScreen

/**
 * Jetpack Navigation Compose host for the AETHRA Android app.
 *
 * Routes:
 *   - home      -> existing AthraHomePage (requires Runtime)
 *   - settings  -> SettingsScreen (Android configuration only)
 *   - voice     -> VoiceScreen shell (no STT/TTS/wake-word behavior yet)
 *   - device    -> DeviceScreen shell (no phone/device adapters yet)
 *
 * Voice and Device screens are intentionally empty shells. They provide
 * navigation destinations ready for future real implementations without
 * pretending missing features are implemented.
 */
@Composable
fun AppNavigation(
    runtime: org.aethra.assistant.aethra.runtime.Runtime,
    visualModeState: VisualModeState,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            AthraHomePage(
                runtime = runtime,
                onNavigateToSettings = {
                    navController.navigate("settings")
                    Unit
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                visualModeState = visualModeState,
                onNavigateHome = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                    Unit
                }
            )
        }

        composable("voice") {
            VoiceScreen()
        }

        composable("device") {
            DeviceScreen()
        }
    }
}
