package com.snapshotpb.configuration.common.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Connects the browser back and forward buttons to [backStack] through [HistoryNavigator]. Only the
 * web target has a browser history, so the other targets do nothing.
 */
@Composable
internal expect fun BrowserHistoryEffect(backStack: NavBackStack<NavKey>)
