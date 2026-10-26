// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.distillery.stt.ui.screens.HomeScreen
import app.distillery.stt.ui.screens.LicenseTextScreen
import app.distillery.stt.ui.screens.LicensesScreen
import app.distillery.stt.ui.screens.ModelsScreen
import app.distillery.stt.ui.screens.TestScreen

private object Routes {
    const val HOME = "home"
    const val MODELS = "models"
    const val TEST = "test"
    const val LICENSES = "licenses"
    const val LICENSE = "license/{id}"
    fun license(id: String) = "license/$id"
}

@Composable
fun DistilleryNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenModels = { nav.navigate(Routes.MODELS) },
                onOpenTest = { nav.navigate(Routes.TEST) },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.MODELS) { ModelsScreen(onBack = back) }
        composable(Routes.TEST) { TestScreen(onBack = back, onOpenModels = { nav.navigate(Routes.MODELS) }) }
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
