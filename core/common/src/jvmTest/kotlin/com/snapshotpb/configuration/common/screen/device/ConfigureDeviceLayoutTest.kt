package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.RENDER_DENSITY
import com.snapshotpb.configuration.common.layout.ScrollTarget
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutDevice
import com.snapshotpb.configuration.common.layout.unclippedBoundsInRoot
import com.snapshotpb.configuration.common.screen.firmware.FirmwareSectionAction
import com.snapshotpb.configuration.common.screen.firmware.FirmwareSectionContent
import com.snapshotpb.configuration.common.screen.firmware.FirmwareSectionState
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.model.DISPLAY_PANEL_HEIGHT
import com.snapshotpb.configuration.model.DISPLAY_PANEL_WIDTH
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_display_window_drawing
import com.snapshotpb.core.resources.configure_device_screen_save_button
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layout checks for [ConfigureDeviceContent] and the panel drawing at every [DuoSize]. */
class ConfigureDeviceLayoutTest {

    private val device = layoutDevice()

    private val draft = ConfigureDeviceDraft()
        .hydratedFrom(device)
        .copy(displayWindowCustom = true)

    private val firmwareState = FirmwareSectionState(
        status = FirmwareStatus(
            deviceId = device.id.toString(),
            connected = true,
            boardVersion = FirmwareVersion(2026, 29, 1, dev = false),
            target = HardwareTarget(board = "snapshot_v2", qualifiers = "esp32c3"),
            latest = FirmwareManifest(
                version = LATEST_VERSION,
                tag = "v$LATEST_VERSION",
                channel = FirmwareChannel.STABLE,
                compatibleHardware = listOf("snapshot_v2"),
                assets = FirmwareAssets(
                    image = FirmwareAsset(
                        name = "snapshot_v2-$LATEST_VERSION-app.signed.bin",
                        url = "https://snapshotpb.github.io/Firmware/snapshot_v2/$LATEST_VERSION/snapshot_v2-$LATEST_VERSION-app.signed.bin",
                        size = 900_000L,
                        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    ),
                ),
            ),
            reason = null,
        ),
        action = FirmwareSectionAction.UPDATE,
    )

    @Test
    fun configureDeviceFitsEveryDuoSize() {
        val drawingLabel = runBlocking { getString(Res.string.configure_device_screen_display_window_drawing) }
        val saveLabel = runBlocking { getString(Res.string.configure_device_screen_save_button) }
        val scrollTargets = listOf(
            ScrollTarget("drawing", hasContentDescription(drawingLabel)),
            ScrollTarget("save", hasText(saveLabel)),
        )

        for (size in DuoSize.entries) {
            val report = checkScreenLayout("configure", size, scrollTargets) {
                SampleConfigureDevice()
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    /** Checks that the panel drawing keeps the panel ratio and stays visible after scroll. */
    @Test
    fun panelDrawing_staysSquareAndVisible() {
        val drawingLabel = runBlocking { getString(Res.string.configure_device_screen_display_window_drawing) }
        val drawingMatcher = hasContentDescription(drawingLabel)
        val scrollTargets = listOf(ScrollTarget("drawing", drawingMatcher))

        for (size in DuoSize.entries) {
            checkScreenLayout(
                screen = "configure",
                size = size,
                scrollTargets = scrollTargets,
                inspect = { inspectedSize ->
                    val bounds = onNode(drawingMatcher, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .unclippedBoundsInRoot()
                    val expectedWidth = bounds.height * DISPLAY_PANEL_WIDTH / DISPLAY_PANEL_HEIGHT
                    val windowWidthPx = inspectedSize.width.value * RENDER_DENSITY
                    val windowHeightPx = inspectedSize.height.value * RENDER_DENSITY

                    assertTrue(
                        bounds.height > 0f,
                        "${inspectedSize.label}: drawing height must be positive (got ${bounds.height} px)",
                    )
                    assertTrue(
                        abs(bounds.width - expectedWidth) <= DRAWING_ASPECT_TOLERANCE_PX,
                        "${inspectedSize.label}: drawing width ${bounds.width} px does not match $expectedWidth px (height=${bounds.height} px)",
                    )
                    assertTrue(
                        bounds.top >= 0f,
                        "${inspectedSize.label}: drawing top ${bounds.top} px is above the window",
                    )
                    assertTrue(
                        bounds.bottom <= windowHeightPx,
                        "${inspectedSize.label}: drawing bottom ${bounds.bottom} px is below window height $windowHeightPx px",
                    )
                    assertTrue(
                        bounds.width <= windowWidthPx,
                        "${inspectedSize.label}: drawing width ${bounds.width} px exceeds window width $windowWidthPx px",
                    )
                },
            ) {
                SampleConfigureDevice()
            }
        }
    }

    @Composable
    private fun SampleConfigureDevice() {
        ConfigureDeviceContent(
            device = device,
            draft = draft,
            onEdit = {},
            onSave = {},
            firmwareSection = {
                FirmwareSectionContent(
                    state = firmwareState,
                    onOpenUpdate = {},
                    onCheckAgain = {},
                )
            },
        )
    }

    private companion object {
        const val LATEST_VERSION = "2026.43.1"
        const val DRAWING_ASPECT_TOLERANCE_PX = 2f
    }
}
