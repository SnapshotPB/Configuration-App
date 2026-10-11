package com.snapshotpb.composite.util

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.w3c.dom.Element

/**
 * Reads and inspects an Android `strings.xml` file.
 *
 * The rules here decide whether the localization check passes, so they are unit-tested in
 * `StringResourceTest.kt` — the same reason the version mapping is unit-tested.
 */
object StringResource {

    /**
     * Matches a Java format specifier, such as `%1$s`, `%d` or the literal `%%`.
     *
     * The flag characters leave out the space flag that `java.util.Formatter` accepts. French
     * and German typography puts a space in front of a percent sign, so `100 % de la batterie`
     * would otherwise read as the specifier `% d`. The space flag has no use in a string
     * resource, and a wrong finding here fails the build for a correct translation.
     *
     * The conversion characters are the set that `java.util.Formatter` accepts. A letter
     * outside that set, such as the `z` of `100%zip`, is not a specifier.
     */
    private val FORMAT_SPECIFIER =
        Regex("""%%|%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtTn])""")

    /** One format specifier. [index] is null when the specifier gives no argument position. */
    data class Specifier(val index: Int?, val conversion: Char) {
        override fun toString(): String = if (index == null) "%$conversion" else "%$index\$$conversion"
    }

    /** The `<string>` elements of one file, and the names that the file repeats. */
    class Parsed(val strings: Map<String, String>, val duplicates: List<String>)

    /**
     * Returns the name and the value of each `<string>` element in [file], in file order.
     *
     * A repeated name is a defect: the last value wins and the earlier one disappears with no
     * message. [Parsed.duplicates] therefore reports every repeated name.
     */
    fun read(file: File): Parsed {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // The input is a file in this repository, but a doctype declaration gives an XML
        // parser an external entity to fetch. Refuse it.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = try {
            factory.newDocumentBuilder().parse(file)
        } catch (error: Exception) {
            throw GradleException("Cannot parse ${file.path}: ${error.message}", error)
        }
        val nodes = document.getElementsByTagName("string")
        val strings = LinkedHashMap<String, String>(nodes.length)
        val duplicates = mutableListOf<String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (strings.put(name, element.textContent) != null) duplicates += name
        }
        return Parsed(strings, duplicates)
    }

    /**
     * Returns the format specifiers in [value], in the order they appear. A literal `%%`
     * prints a percent sign and takes no argument, so it is left out.
     */
    fun formatSpecifiers(value: String): List<Specifier> = FORMAT_SPECIFIER
        .findAll(value)
        .filterNot { match -> match.value == "%%" }
        .map { match ->
            Specifier(
                index = match.groupValues[1].toIntOrNull(),
                conversion = match.groupValues[2].first()
            )
        }
        .toList()

    /**
     * Reports whether AAPT2 refuses [value]. A value that the author put in double quotes
     * keeps its apostrophes. Every other apostrophe needs a backslash in front of it.
     *
     * The double-quote form must wrap the whole value. `"a" and 'b'` starts and ends with a
     * double quote but is not wrapped, so the apostrophe in it is still a finding.
     */
    fun hasUnescapedApostrophe(value: String): Boolean {
        var index = 0
        var quoted = false
        while (index < value.length) {
            when (value[index]) {
                '\\' -> index += 2
                '"' -> {
                    quoted = !quoted
                    index += 1
                }
                '\'' -> if (quoted) index += 1 else return true
                else -> index += 1
            }
        }
        return false
    }

    /**
     * Reports whether [value] contains words. A value that holds only format specifiers,
     * digits or punctuation, such as `%1$d/%2$d` or `+`, is the same in every locale, so the
     * check must not report it as untranslated.
     */
    fun isTranslatable(value: String): Boolean =
        FORMAT_SPECIFIER.replace(value, "").any { character -> character.isLetter() }
}
