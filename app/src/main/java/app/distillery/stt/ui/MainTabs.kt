// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.ui

import app.distillery.stt.DistilleryApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import android.widget.Toast
import android.os.SystemClock
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import app.distillery.stt.R
import app.distillery.stt.feature.TextToSpeechTab
import app.distillery.stt.ui.screens.DownloadsTab
import app.distillery.stt.ui.screens.SpeechToTextTab
import kotlinx.coroutines.launch

/** The app's sections. Outlined icons, filled while the tab is selected, as in Contacts and Dialtone. */
enum class MainTab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    SpeechToText("Speech to text", R.drawable.ic_mic_outline, R.drawable.ic_mic),
    TextToSpeech("Text to speech", R.drawable.ic_volume_up_outline, R.drawable.ic_volume_up),
    Downloads("Downloads", R.drawable.ic_download_outline, R.drawable.ic_download),
}

/** Windows at least this wide (tablets, desktops) get a navigation rail instead of a bottom bar. */
private const val RAIL_MIN_WIDTH_DP = 840

/**
 * The three tabs as swipeable pages, with Material 3's short navigation bar at the
 * bottom (a rail on wide windows). Each page draws its own top bar. Back from the
 * second or third tab returns to the first instead of leaving the app.
 *
 * @param requestedTab a tab another screen asked to show; [onRequestHandled] clears it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainTabs(
    requestedTab: MainTab?,
    onRequestHandled: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenLicenses: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val tabs = MainTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    // targetPage, so the bar shows where a swipe or tap is heading without flickering
    // through the middle tab.
    val current = tabs[pager.targetPage]
    val select: (MainTab) -> Unit = { tab ->
        if (pager.currentPage != tab.ordinal) scope.launch { pager.animateScrollToPage(tab.ordinal) }
    }

    val context = LocalContext.current
    val testTaps = remember { TestTaps(context) }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            pager.scrollToPage(requestedTab.ordinal)
            onRequestHandled()
        }
    }
    BackHandler(enabled = pager.currentPage != MainTab.SpeechToText.ordinal) { select(MainTab.SpeechToText) }

    val openDownloads = { select(MainTab.Downloads) }
    val pages: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(state = pager, modifier = modifier, key = { tabs[it] }) { page ->
            when (tabs[page]) {
                MainTab.SpeechToText -> SpeechToTextTab(
                    onOpenDownloads = openDownloads,
                    onOpenTest = onOpenTest,
                    onOpenLicenses = onOpenLicenses,
                )
                MainTab.TextToSpeech -> TextToSpeechTab(
                    onOpenDownloads = openDownloads,
                    onNavigate = onNavigate,
                    onOpenLicenses = onOpenLicenses,
                )
                MainTab.Downloads -> DownloadsTab(onOpenLicenses = onOpenLicenses)
            }
        }
    }

    if (LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = tab == current,
                        onClick = { if (tab == current) testTaps.tap(tab) else select(tab) },
                        icon = { TabIcon(tab, tab == current) },
                        label = { Text(tab.label) },
                    )
                }
            }
            // The rail covers the start inset, so the pages mustn't add it again.
            pages(
                Modifier
                    .weight(1f)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                ShortNavigationBar {
                    tabs.forEach { tab ->
                        ShortNavigationBarItem(
                            selected = tab == current,
                            onClick = { if (tab == current) testTaps.tap(tab) else select(tab) },
                            icon = { TabIcon(tab, tab == current) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // Pages own their top bar and the top inset, so only the bar's height is passed
            // on. It's consumed too: the bar already covers the gesture area, and a page's
            // own scaffold would otherwise leave a gap for it above the bar.
            val bottom = padding.calculateBottomPadding()
            pages(
                Modifier
                    .padding(bottom = bottom)
                    .consumeWindowInsets(PaddingValues(bottom = bottom)),
            )
        }
    }
}

@Composable
private fun TabIcon(tab: MainTab, selected: Boolean) {
    // The label already names the tab, so the icon is decorative.
    Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
}

/**
 * Tapping the selected tab's button [TAPS] times in a row shows or hides that
 * tab's Test row, like Android's own developer options. Taps more than
 * [GAP_MS] apart start the count again.
 */
private class TestTaps(private val context: Context) {
    private var tab: MainTab? = null
    private var count = 0
    private var last = 0L
    private var toast: Toast? = null

    fun tap(on: MainTab) {
        val settings = (context.applicationContext as DistilleryApplication).settings
        val shown = when (on) {
            MainTab.SpeechToText -> settings.showSttTest
            MainTab.TextToSpeech -> settings.showTtsTest
            MainTab.Downloads -> return
        }
        val now = SystemClock.elapsedRealtime()
        if (on != tab || now - last > GAP_MS) count = 0
        tab = on
        last = now
        count++
        val left = TAPS - count
        when {
            left == 0 -> {
                count = 0
                when (on) {
                    MainTab.SpeechToText -> settings.showSttTest = !shown
                    MainTab.TextToSpeech -> settings.showTtsTest = !shown
                    MainTab.Downloads -> Unit
                }
                show(if (shown) "Test hidden" else "Test is now shown at the bottom of this tab")
            }
            left <= 3 -> show("${if (shown) "Hide" else "Show"} Test in $left more tap${if (left == 1) "" else "s"}")
        }
    }

    private fun show(text: String) {
        toast?.cancel()
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        const val TAPS = 8
        const val GAP_MS = 1_500L
    }
}
