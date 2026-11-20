package org.aethra.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.aethra.assistant.aethra.navigation.AppNavigation
import org.aethra.assistant.aethra.runtime.Runtime
import org.aethra.assistant.aethra.runtime.createRuntime
import org.aethra.assistant.aethra.ui.VisualModeState

class MainActivity : ComponentActivity() {
    private var runtime: Runtime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appRuntime = createRuntime(applicationContext)
        runtime = appRuntime
        appRuntime.start()

        val visualModeState = VisualModeState()

        // Use locale-aware layout direction: RTL for Arabic locales,
        // LTR for English and other locales. Do not hard-code RTL for all users.
        val layoutDirection = if (java.util.Locale.getDefault().language == "ar") {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    AppNavigation(
                        runtime = appRuntime,
                        visualModeState = visualModeState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runtime?.shutdown()
        runtime = null
        super.onDestroy()
    }
}
