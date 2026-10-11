package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.newDeviceName
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard for issue #156. A new device gets `device_default_name`, and the Configure Device screen
 * cuts a name to [Device.MAX_NAME_LENGTH]. A longer default name in a locale thus renames a new
 * device at its first Save.
 *
 * The test reads the `strings.xml` files of `core:resources`. The `jvmTest` task declares that
 * directory as an input in `build.gradle.kts`, so a change to a string runs the test again.
 */
class DefaultDeviceNameTest {

    @Test
    fun theDefaultNameFitsTheNameLimitInEveryLocale() {
        val localeDirs = File(COMPOSE_RESOURCES_DIR).listFiles { file -> file.name.startsWith("values") }
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
        assertTrue(localeDirs.size > 1, "no locales found in $COMPOSE_RESOURCES_DIR")

        for (dir in localeDirs) {
            val name = defaultName(File(dir, "strings.xml")) ?: continue
            assertTrue(name.isNotBlank(), "${dir.name}: the default name is blank")
            assertTrue(
                name.length <= Device.MAX_NAME_LENGTH,
                "${dir.name}: \"$name\" is longer than ${Device.MAX_NAME_LENGTH} characters",
            )
            assertEquals(name, newDeviceName(BOARD_ADVERTISED_NAME, name), dir.name)
        }
    }

    /** The value of `device_default_name` in [file], or null when the locale does not set it. */
    private fun defaultName(file: File): String? {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "device_default_name" }
            ?.textContent
    }

    private companion object {
        /** Relative to the project directory, which is the working directory of `jvmTest`. */
        const val COMPOSE_RESOURCES_DIR = "../resources/src/commonMain/composeResources"
    }
}
