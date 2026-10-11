@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.unsupported_browser_details_firefox
import com.snapshotpb.core.resources.unsupported_browser_details_hide
import com.snapshotpb.core.resources.unsupported_browser_details_show
import com.snapshotpb.core.resources.unsupported_browser_details_webkit
import com.snapshotpb.core.resources.unsupported_browser_message
import com.snapshotpb.core.resources.unsupported_browser_title
import com.snapshotpb.core.resources.unsupported_browser_webkit_bug_link
import org.jetbrains.compose.resources.stringResource

/**
 * Hard block shown when the browser has no Web Bluetooth (`navigator.bluetooth`). Recommends a
 * Chromium browser (Chrome/Edge/Opera) — deliberately NOT Firefox, which also lacks Web Bluetooth for a different reason (Mozilla declined it on privacy
 * grounds) than Safari/iOS (Apple has not implemented it; tracked in the linked WebKit bug). The
 * net consumer fact: web BLE will never work on iPhone/iPad, where every browser is WebKit — the
 * native iOS app remains the only BLE path there.
 */
@Composable
fun UnsupportedBrowserScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.unsupported_browser_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.unsupported_browser_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) Res.string.unsupported_browser_details_hide
                            else Res.string.unsupported_browser_details_show
                        )
                    )
                }
                if (expanded) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.unsupported_browser_details_webkit),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.unsupported_browser_details_firefox),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { openUrl(WEBKIT_BUG_URL) }) {
                                Text(stringResource(Res.string.unsupported_browser_webkit_bug_link))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val WEBKIT_BUG_URL = "https://bugs.webkit.org/show_bug.cgi?id=101034"

private fun openUrl(url: String): Unit = js("window.open(url, '_blank')")