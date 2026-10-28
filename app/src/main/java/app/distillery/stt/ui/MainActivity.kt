// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui

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
        val openRoute = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN) else null
        setContent {
            DistilleryTheme {
                DistilleryNavHost(openRoute = openRoute)
            }
        }
    }

    companion object {
        /** Optional screen to open on launch, e.g. from another part of the app. */
        const val EXTRA_OPEN = "app.distillery.stt.extra.OPEN"
    }
}
