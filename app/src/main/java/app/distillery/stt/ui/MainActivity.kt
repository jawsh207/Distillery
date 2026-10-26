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
        setContent {
            DistilleryTheme {
                DistilleryNavHost()
            }
        }
    }
}
