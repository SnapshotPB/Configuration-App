package com.snapshotpb.configuration.bluetooth

import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.engine.macos.jvm.MacosJvmEngine
import dev.bluefalcon.engine.rpi.RpiEngine
import dev.bluefalcon.engine.windows.WindowsEngine

private val log = Arbor.tag("BlueFalconEngineFactory")

/**
 * Single source of truth for the desktop BlueFalcon engine selection (by `os.name`). Shared by the
 * production DI (`platform/desktop/di` `BluetoothModule`) and the reliability harness
 * (`DesktopReliabilityRunner`), so both provably exercise the *same* engine the app ships.
 */
fun selectBlueFalconEngine(): BlueFalconEngine {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") || osName.contains("darwin") -> MacosJvmEngine()
        osName.contains("win") -> WindowsEngine()
        osName.contains("nux") || osName.contains("nix") -> RpiEngine()
        else -> error("Unsupported desktop OS for BlueFalcon: $osName")
    }.also { engine ->
        log.i { "Selected BlueFalcon engine ${engine::class.simpleName} for $osName" }
    }
}
