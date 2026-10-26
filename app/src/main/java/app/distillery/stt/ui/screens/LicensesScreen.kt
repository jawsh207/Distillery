// SPDX-License-Identifier: GPL-3.0-or-later
// ListItem(headlineContent = …) and Slider(value = …) are deprecated on the
// Material 3 1.6 line in favour of new overloads; they're kept here because they
// exist on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.distillery.stt.BuildConfig
import app.distillery.stt.R
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    DistilleryScreen(title = "Licenses", subtitle = "Distillery ${BuildConfig.VERSION_NAME} · free and open source", onBack = onBack) {
        item {
            BodyText(
                "Distillery is free software under the GNU General Public License, version 3 or later. " +
                    "Every library and model it uses is open source; tap one to read its license.",
            )
        }
        OssLicenses.groups.forEach { group ->
            item(key = "group-${group.title}") {
                Text(
                    group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            item(key = "items-${group.title}") {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        group.components.forEach { component ->
                            ListItem(
                                headlineContent = { Text(component.name) },
                                supportingContent = { Text(component.detail) },
                                trailingContent = {
                                    AssistChip(onClick = { onOpen(component.id) }, label = { Text(component.license) })
                                },
                                leadingContent = { Icon(painterResource(R.drawable.ic_description), contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(component.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LicenseTextScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val component = remember(id) { OssLicenses.find(id) }
    var paragraphs by remember(id) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(component) {
        paragraphs = component?.let {
            withContext(Dispatchers.IO) {
                context.assets.open("licenses/${it.asset}").bufferedReader().use { r -> r.readText() }
            }
                // Lazy rendering: split long texts into paragraphs.
                .split(Regex("\\n\\s*\\n"))
        } ?: listOf("License text not found.")
    }

    DistilleryScreen(
        title = component?.name ?: "License",
        subtitle = component?.license,
        onBack = onBack,
    ) {
        val text = paragraphs
        if (text == null) {
            item { BodyText("Loading…") }
        } else {
            items(text.size) { i ->
                Text(
                    text[i].trimEnd(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
