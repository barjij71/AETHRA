package org.aethra.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.aethra.assistant.aethra.runtime.Runtime
import org.aethra.assistant.aethra.runtime.createRuntime

class MainActivity : ComponentActivity() {
    private var runtime: Runtime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appRuntime = createRuntime(applicationContext)
        runtime = appRuntime
        appRuntime.start()

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AthraHomePage(appRuntime)
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
