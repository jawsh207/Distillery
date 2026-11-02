// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.distillery.stt.feature.TtsFeature
import app.distillery.stt.ui.screens.LicenseTextScreen
import app.distillery.stt.ui.screens.LicensesScreen
import app.distillery.stt.ui.screens.TestScreen

private object Routes {
    const val MAIN = "main"
    const val TEST = "test"
    const val LICENSES = "licenses"
    const val LICENSE = "license/{id}"
    fun license(id: String) = "license/$id"
}

/**
 * The tabs, and the screens they open on top: the tests, pronunciations and licenses.
 *
 * @param openRoute a screen to open on launch; [TtsFeature.VOICES_ROUTE] opens the Downloads tab.
 */
@Composable
fun DistilleryNavHost(openRoute: String? = null) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val navigate: (String) -> Unit = { route -> nav.navigate(route) }
    var requestedTab by rememberSaveable {
        mutableStateOf(if (openRoute == TtsFeature.VOICES_ROUTE) MainTab.Downloads else null)
    }
    // From a screen on top of the tabs: go back to them, showing Downloads.
    val openDownloads: () -> Unit = {
        requestedTab = MainTab.Downloads
        nav.popBackStack(Routes.MAIN, inclusive = false)
    }
    LaunchedEffect(openRoute) {
        if (openRoute != null && TtsFeature.handlesRoute(openRoute)) nav.navigate(openRoute)
    }
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainTabs(
                requestedTab = requestedTab,
                onRequestHandled = { requestedTab = null },
                onOpenTest = { nav.navigate(Routes.TEST) },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
                onNavigate = navigate,
            )
        }
        composable(Routes.TEST) {
            TestScreen(onBack = back, onOpenModels = openDownloads)
        }
        with(TtsFeature) { destinations(onBack = back, onOpenDownloads = openDownloads, onNavigate = navigate) }
        composable(Routes.LICENSES) {
            LicensesScreen(onBack = back, onOpen = { id -> nav.navigate(Routes.license(id)) })
        }
        composable(
            Routes.LICENSE,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            LicenseTextScreen(id = entry.arguments?.getString("id").orEmpty(), onBack = back)
        }
    }
}
