package com.snapshotpb.configuration.common.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/** No browser history outside the web target. */
@Composable
internal actual fun BrowserHistoryEffect(backStack: NavBackStack<NavKey>) = Unit
