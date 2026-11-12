// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.distillery.stt.ui.theme.DistilleryTheme

/**
 * The app UI: setup, model downloads, a SpeechRecognizer test bench and
 * licenses. Also the settings activity Android links to from the voice input
 * picker and from Distillery's App info page.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openRoute = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            DistilleryTheme {
                DistilleryNavHost(openRoute = openRoute, onOpened = { openRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRoute = it }
    }

    /** A screen another part of the app asked to open, until it's shown. */
    private var openRoute by mutableStateOf<String?>(null)

    companion object {
        /** Optional screen to open on launch, e.g. from another part of the app. */
        const val EXTRA_OPEN = "app.distillery.stt.extra.OPEN"
    }
}
