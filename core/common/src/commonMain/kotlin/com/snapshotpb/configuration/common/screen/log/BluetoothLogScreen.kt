package com.snapshotpb.configuration.common.screen.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSource
import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.bluetooth_log_board_absent
import com.snapshotpb.core.resources.bluetooth_log_board_failed
import com.snapshotpb.core.resources.bluetooth_log_board_not_visible
import com.snapshotpb.core.resources.bluetooth_log_clear_button
import com.snapshotpb.core.resources.bluetooth_log_dropped
import com.snapshotpb.core.resources.bluetooth_log_empty
import com.snapshotpb.core.resources.bluetooth_log_export_button
import com.snapshotpb.core.resources.bluetooth_log_export_title
import com.snapshotpb.core.resources.bluetooth_log_no_session
import com.snapshotpb.core.resources.bluetooth_log_requested
import com.snapshotpb.core.resources.bluetooth_log_source_app
import com.snapshotpb.core.resources.bluetooth_log_source_board
import com.snapshotpb.core.resources.bluetooth_log_title
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

/** Test tag for the Export button on the Bluetooth log screen. */
const val BLUETOOTH_LOG_EXPORT_TAG = "bluetoothLogExport"

/** Test tag for the Clear button on the Bluetooth log screen. */
const val BLUETOOTH_LOG_CLEAR_TAG = "bluetoothLogClear"

/** Test tag for the entry list on the Bluetooth log screen. */
const val BLUETOOTH_LOG_LIST_TAG = "bluetoothLogList"

/** The Bluetooth log screen. It shows the board log lines and the app events. */
@Composable
fun BluetoothLogScreen() {
    val viewModel: BluetoothLogViewModel by rememberViewModel()
    val snapshot by viewModel.state.collectAsState()
    val canExport by viewModel.canExport.collectAsState()
    val exportTitle = stringResource(Res.string.bluetooth_log_export_title)

    BluetoothLogContent(
        snapshot = snapshot,
        canExport = canExport,
        onExport = { viewModel.export(exportTitle) },
        onClear = viewModel::clear,
    )
}

/** The Bluetooth log layout for [snapshot], with no view model. */
@Composable
internal fun BluetoothLogContent(
    snapshot: BluetoothLogSnapshot,
    canExport: Boolean,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.bluetooth_log_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (canExport) {
                    TextButton(
                        onClick = onExport,
                        modifier = Modifier.testTag(BLUETOOTH_LOG_EXPORT_TAG),
                    ) {
                        Text(stringResource(Res.string.bluetooth_log_export_button))
                    }
                }
                TextButton(
                    onClick = onClear,
                    enabled = snapshot.entries.isNotEmpty(),
                    modifier = Modifier.testTag(BLUETOOTH_LOG_CLEAR_TAG),
                ) {
                    Text(stringResource(Res.string.bluetooth_log_clear_button))
                }
            }

            boardLogStateMessage(snapshot.boardLogState)?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (snapshot.droppedChunks > 0) {
                Text(
                    text = stringResource(Res.string.bluetooth_log_dropped, snapshot.droppedChunks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Space(8.dp)

            if (snapshot.entries.isEmpty()) {
                Text(
                    text = stringResource(Res.string.bluetooth_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val listState = rememberLazyListState()
                // True while the user is at the end of the list. A new entry then scrolls the list to the end.
                var followTail by remember { mutableStateOf(true) }
                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }
                        .filter { inProgress -> !inProgress }
                        .collect { followTail = !listState.canScrollForward }
                }
                val lastSequence = snapshot.entries.lastOrNull()?.sequence
                LaunchedEffect(lastSequence) {
                    if (followTail && snapshot.entries.isNotEmpty()) {
                        listState.scrollToItem(snapshot.entries.lastIndex)
                    }
                }

                val boardLabel = stringResource(Res.string.bluetooth_log_source_board)
                val appLabel = stringResource(Res.string.bluetooth_log_source_app)
                val timeZone = remember { TimeZone.currentSystemDefault() }
                val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
                val boardColor = MaterialTheme.colorScheme.tertiary
                val appColor = MaterialTheme.colorScheme.primary
                val textColor = MaterialTheme.colorScheme.onSurface

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(BLUETOOTH_LOG_LIST_TAG),
                ) {
                    items(snapshot.entries, key = { it.sequence }) { entry ->
                        val line = remember(entry, timeZone, boardLabel, appLabel, timeColor, boardColor, appColor) {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = timeColor)) {
                                    append(bluetoothLogClockTime(entry.timeMillis, timeZone))
                                }
                                append(' ')
                                val (sourceLabel, sourceColor) = when (entry.source) {
                                    BluetoothLogSource.BOARD -> boardLabel to boardColor
                                    BluetoothLogSource.APP -> appLabel to appColor
                                }
                                withStyle(SpanStyle(color = sourceColor)) {
                                    append(sourceLabel)
                                }
                                append(' ')
                                append(entry.text)
                            }
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The text for [state], or null for RECEIVING, which needs no text. */
internal fun boardLogStateMessage(state: BoardLogState): StringResource? = when (state) {
    BoardLogState.NO_SESSION -> Res.string.bluetooth_log_no_session
    BoardLogState.REQUESTED -> Res.string.bluetooth_log_requested
    BoardLogState.RECEIVING -> null
    BoardLogState.ABSENT -> Res.string.bluetooth_log_board_absent
    BoardLogState.NOT_VISIBLE -> Res.string.bluetooth_log_board_not_visible
    BoardLogState.FAILED -> Res.string.bluetooth_log_board_failed
}
