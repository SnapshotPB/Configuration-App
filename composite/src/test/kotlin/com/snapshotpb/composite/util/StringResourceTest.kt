package com.snapshotpb.composite.util

import com.snapshotpb.composite.util.StringResource.Specifier
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the rules that `LocalizationCheckTask` applies to a `strings.xml` file.
 *
 * These rules decide whether the build fails, so a wrong rule either lets a broken
 * translation reach the app or stops a correct translation. That makes them worth a test,
 * for the same reason `Version.kt` has one.
 */
class StringResourceTest {

    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun deleteTemporaryFiles() {
        temporaryFiles.forEach { file -> file.delete() }
    }

    private fun stringsFile(body: String): File {
        val file = File.createTempFile("strings", ".xml")
        temporaryFiles += file
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body\n</resources>")
        return file
    }

    // --- formatSpecifiers -------------------------------------------------------------

    @Test
    fun `a value with no format specifier has an empty list`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("Configure Device"))
    }

    @Test
    fun `positional specifiers keep their index and conversion`() {
        assertEquals(
            listOf(Specifier(1, 'd'), Specifier(2, 'd')),
            StringResource.formatSpecifiers("%1\$d/%2\$d")
        )
    }

    @Test
    fun `a specifier with no argument position has a null index`() {
        assertEquals(listOf(Specifier(null, 's')), StringResource.formatSpecifiers("Version %s"))
    }

    @Test
    fun `a literal percent sign takes no argument`() {
        assertEquals(
            listOf(Specifier(1, 'd')),
            StringResource.formatSpecifiers("%1\$d%% brightness")
        )
    }

    @Test
    fun `a space before a percent sign is not a specifier`() {
        // French and German typography writes `100 %`. The space flag that java.util.Formatter
        // accepts would otherwise read this as the specifier `% d` and fail a correct
        // translation. This is the regression the reviewers found.
        assertEquals(emptyList(), StringResource.formatSpecifiers("100 % de la batterie"))
        assertEquals(emptyList(), StringResource.formatSpecifiers("Helligkeit 50 % maximal"))
    }

    @Test
    fun `a letter that is not a conversion character is not a specifier`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("100%zip"))
    }

    @Test
    fun `a percent sign followed by a conversion character is a specifier`() {
        // `100%des` really would consume an integer argument, so the finding is correct.
        assertEquals(listOf(Specifier(null, 'd')), StringResource.formatSpecifiers("100%des"))
    }

    @Test
    fun `a changed conversion character is a different specifier`() {
        assertNotEquals(
            StringResource.formatSpecifiers("Version %1\$s"),
            StringResource.formatSpecifiers("Version %1\$d")
        )
    }

    @Test
    fun `a repeated argument position appears twice`() {
        // The task compares by index, not by count, so a translation may repeat an argument.
        assertEquals(
            listOf(Specifier(1, 's'), Specifier(1, 's')),
            StringResource.formatSpecifiers("%1\$s (%1\$s)")
        )
    }

    @Test
    fun `a width and a precision do not change the specifier`() {
        assertEquals(listOf(Specifier(1, 'f')), StringResource.formatSpecifiers("%1\$.2f ms"))
    }

    // --- hasUnescapedApostrophe -------------------------------------------------------

    @Test
    fun `a bare apostrophe is a finding`() {
        assertTrue(StringResource.hasUnescapedApostrophe("Couldn't read device"))
    }

    @Test
    fun `a backslash escapes the apostrophe`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Couldn\\'t read device"))
    }

    @Test
    fun `a value wrapped in double quotes keeps its apostrophes`() {
        assertFalse(StringResource.hasUnescapedApostrophe("\"Couldn't read device\""))
    }

    @Test
    fun `a value that only starts and ends with a quote is not wrapped`() {
        assertTrue(StringResource.hasUnescapedApostrophe("\"a\" and 'b'"))
    }

    @Test
    fun `a value with no apostrophe is clean`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Gerät konnte nicht gelesen werden"))
        assertFalse(StringResource.hasUnescapedApostrophe("Impossible de lire l’appareil"))
    }

    @Test
    fun `an apos entity decodes to a bare apostrophe and is a finding`() {
        // CLAUDE.md records that bare `'` and `&apos;` both fail AAPT2. The XML parser turns
        // `&apos;` into `'`, so the rule must see it after the parse, not in the raw file.
        val file = stringsFile("""    <string name="a">Couldn&apos;t read device</string>""")
        val value = StringResource.read(file).strings.getValue("a")
        assertEquals("Couldn't read device", value)
        assertTrue(StringResource.hasUnescapedApostrophe(value))
    }

    // --- isTranslatable ---------------------------------------------------------------

    @Test
    fun `a value with words is translatable`() {
        assertTrue(StringResource.isTranslatable("Save Device"))
        assertTrue(StringResource.isTranslatable("Version %1\$s"))
    }

    @Test
    fun `a value of only specifiers and punctuation is not translatable`() {
        // These are identical in every locale, so the check must stay quiet about them.
        assertFalse(StringResource.isTranslatable("%1\$d/%2\$d"))
        assertFalse(StringResource.isTranslatable("%1\$s • [#%2\$s]"))
        assertFalse(StringResource.isTranslatable("+"))
        assertFalse(StringResource.isTranslatable("-"))
    }

    // --- read -------------------------------------------------------------------------

    @Test
    fun `read returns each string in file order and ignores a comment`() {
        val file = stringsFile(
            """
            |    <string name="app_name">Snapshot PB</string>
            |    <!-- A comment must not become a string. -->
            |    <string name="navigation_back">Back</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(
            mapOf("app_name" to "Snapshot PB", "navigation_back" to "Back"),
            parsed.strings
        )
        assertEquals(emptyList(), parsed.duplicates)
    }

    @Test
    fun `a repeated name is reported`() {
        // The last value wins, so the first string disappears with no message.
        val file = stringsFile(
            """
            |    <string name="a">First</string>
            |    <string name="a">Second</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(listOf("a"), parsed.duplicates)
        assertEquals("Second", parsed.strings.getValue("a"))
    }
}
