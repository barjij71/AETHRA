package org.aethra.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.aethra.assistant.aethra.navigation.AppNavigation
import org.aethra.assistant.aethra.runtime.AndroidRuntimeViewModel
import org.aethra.assistant.aethra.ui.VisualModeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AndroidRuntimeViewModel::class.java))
                return AndroidRuntimeViewModel(applicationContext) as T
            }
        }
        val runtimeModel = ViewModelProvider(this, factory)[AndroidRuntimeViewModel::class.java]
        val appRuntime = runtimeModel.runtime
        val approvalUi = runtimeModel.approvalUi

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
                        approvalUi = approvalUi,
                        visualModeState = visualModeState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

}
