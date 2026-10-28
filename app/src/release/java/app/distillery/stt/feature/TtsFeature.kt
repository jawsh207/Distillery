// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.feature

import androidx.compose.foundation.lazy.LazyListScope
import androidx.navigation.NavGraphBuilder
import app.distillery.stt.ui.screens.OssGroup

/**
 * Text to speech is not part of release builds.
 *
 * The engine is built only into dev (debug) builds while the license of one of
 * its data files is being confirmed. This stub keeps the shared UI code the same
 * in both builds; it adds nothing to the app.
 */
object TtsFeature {
    const val ENABLED = false

    val licenseGroups: List<OssGroup> = emptyList()

    fun handlesRoute(route: String): Boolean = false

    fun NavGraphBuilder.destinations(onBack: () -> Unit) = Unit

    fun LazyListScope.homeItems(onNavigate: (String) -> Unit) = Unit

    fun LazyListScope.testItems(onNavigate: (String) -> Unit) = Unit
}
