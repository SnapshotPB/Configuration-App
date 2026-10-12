package com.snapshotpb.composite.task

import com.snapshotpb.composite.util.StringResource
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Compares each translated `strings.xml` with the base locale and reports the differences.
 *
 * The task puts each finding in one of two classes:
 *
 * - **An error fails the build.** A format specifier that asks for an argument the base
 *   string does not supply throws `MissingFormatArgumentException` at run time. A conversion
 *   character that does not agree throws `IllegalFormatConversionException`. An apostrophe
 *   with no escape makes AAPT2 refuse the base file. A repeated `name` silently discards a
 *   string.
 * - **A warning does not fail the build.** An absent key, an unknown key, an absent locale
 *   and a value that is still the English text all show translation drift. A translation
 *   can come after its English string, so drift must never stop the build.
 *
 * The task writes every finding to [reportFile], which also makes the task cacheable. Gradle
 * skips the task body when the inputs did not change, so an empty console is not proof of a
 * clean run — read [reportFile] for that.
 */
@CacheableTask
abstract class LocalizationCheckTask : DefaultTask() {

    /** The base locale file, `values/strings.xml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseFile: RegularFileProperty

    /** Each `values-<qualifier>/strings.xml` file to compare with the base locale. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val translationFiles: ConfigurableFileCollection

    /**
     * The directory name of every locale the app ships, such as `values-de`. A locale that
     * has no directory at all produces no file and would otherwise go unreported, which is
     * the largest gap the check can have.
     */
    @get:Input
    abstract val expectedLocales: SetProperty<String>

    /**
     * The names of the strings that stay in English in every locale, such as a brand name.
     * The task does not report these names as untranslated.
     *
     * An entry is either a bare name, which covers every locale, or `<locale>:<name>`, which
     * covers that one locale. Prefer the scoped form: `common_no` is `No` in Spanish but
     * `Nein` in German, so a bare entry would hide a real German gap.
     */
    @get:Input
    abstract val untranslatedAllowList: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val baseLocale = baseFile.get().asFile.parentFile.name
        val base = StringResource.read(baseFile.get().asFile)
        val allowList = untranslatedAllowList.get()
        val errors = mutableListOf<String>()
        val localeReports = mutableListOf<LocaleReport>()

        base.duplicates.forEach { name ->
            errors += "$baseLocale: $name appears more than once."
        }
        base.strings.forEach { (name, value) ->
            // AAPT2 only ever sees the base locale: `copyStringRes` in app/android copies
            // `values/` alone. An apostrophe in a translation is therefore a warning, below.
            if (StringResource.hasUnescapedApostrophe(value)) {
                errors += "$baseLocale: $name has an apostrophe with no escape."
            }
            val specifiers = StringResource.formatSpecifiers(value)
            if (specifiers.size > 1 && specifiers.any { specifier -> specifier.index == null }) {
                errors += "$baseLocale: $name has more than one format specifier and at least " +
                    "one gives no argument position. A translation cannot reorder these."
            }
        }
        // A stale entry silently weakens the check, so report one that no longer names a string.
        allowList.forEach { entry ->
            val name = entry.substringAfterLast(':')
            if (!base.strings.containsKey(name)) {
                errors += "untranslatedAllowList names $entry, which the base locale does not have."
            }
        }

        val seenLocales = mutableSetOf<String>()
        translationFiles.files.sortedBy { file -> file.absolutePath }.forEach { file ->
            val locale = file.parentFile.name
            seenLocales += locale
            localeReports += compare(locale, base.strings, StringResource.read(file), allowList, errors)
        }

        val absentLocales = expectedLocales.get().filterNot { locale -> locale in seenLocales }

        val report = writeReport(errors, absentLocales, localeReports)
        absentLocales.sorted().forEach { locale ->
            logger.warn("Localization: {} has no strings.xml at all.", locale)
        }
        // The console gets one line for each locale that drifted. The report file holds the
        // name of every string behind these counts.
        localeReports.filter { localeReport -> localeReport.total > 0 }.forEach { localeReport ->
            logger.warn(
                "Localization: {} — {} absent, {} unknown, {} still English, {} other. See {}.",
                localeReport.locale,
                localeReport.absent.size,
                localeReport.unknown.size,
                localeReport.english.size,
                localeReport.other.size,
                report
            )
        }
        if (errors.isNotEmpty()) {
            throw GradleException(
                errors.joinToString(
                    prefix = "The localization check found ${errors.size} error(s):\n  ",
                    separator = "\n  "
                )
            )
        }
    }

    private fun compare(
        locale: String,
        base: Map<String, String>,
        translation: StringResource.Parsed,
        allowList: Set<String>,
        errors: MutableList<String>
    ): LocaleReport {
        val strings = translation.strings
        val absent = base.keys.filterNot { name -> strings.containsKey(name) }
        val unknown = strings.keys.filterNot { name -> base.containsKey(name) }
        val english = mutableListOf<String>()
        val other = mutableListOf<String>()

        translation.duplicates.forEach { name ->
            errors += "$locale: $name appears more than once."
        }

        strings.forEach { (name, value) ->
            val baseValue = base[name] ?: return@forEach
            checkSpecifiers(locale, name, baseValue, value, errors, other)
            if (StringResource.hasUnescapedApostrophe(value)) {
                other += "$name has an apostrophe with no escape"
            }
            val untranslated = value == baseValue && StringResource.isTranslatable(baseValue)
            val allowed = name in allowList || "$locale:$name" in allowList
            if (untranslated && !allowed) english += name
        }
        return LocaleReport(locale, absent, unknown.toList(), english, other)
    }

    /**
     * Compares the format specifiers of one string with the base locale.
     *
     * A translation may put the arguments in a different order, and may repeat one, so the
     * counts and the order carry no meaning. What matters is that the translation asks only
     * for arguments the base string supplies, with the same conversion character. An argument
     * the translation drops is safe at run time, so it is only a warning.
     */
    private fun checkSpecifiers(
        locale: String,
        name: String,
        baseValue: String,
        value: String,
        errors: MutableList<String>,
        other: MutableList<String>
    ) {
        val baseSpecifiers = StringResource.formatSpecifiers(baseValue)
        val specifiers = StringResource.formatSpecifiers(value)
        val baseByIndex = baseSpecifiers
            .mapNotNull { specifier -> specifier.index?.let { index -> index to specifier } }
            .toMap()

        specifiers.filter { specifier -> specifier.index != null }.forEach { specifier ->
            val baseSpecifier = baseByIndex[specifier.index]
            when {
                baseSpecifier == null ->
                    errors += "$locale: $name asks for $specifier, which the base locale does " +
                        "not supply."
                baseSpecifier.conversion != specifier.conversion ->
                    errors += "$locale: $name uses $specifier where the base locale uses " +
                        "$baseSpecifier."
            }
        }

        // A specifier with no argument position is consumed in order, so the counts must agree.
        val baseUnpositioned = baseSpecifiers.count { specifier -> specifier.index == null }
        val unpositioned = specifiers.count { specifier -> specifier.index == null }
        if (baseUnpositioned != unpositioned) {
            errors += "$locale: $name has $unpositioned format specifier(s) with no argument " +
                "position, and the base locale has $baseUnpositioned."
        }

        val used = specifiers.mapNotNull { specifier -> specifier.index }.toSet()
        (baseByIndex.keys - used).sorted().forEach { index ->
            other += "$name does not use argument ${baseByIndex.getValue(index)}"
        }
    }

    private fun writeReport(
        errors: List<String>,
        absentLocales: List<String>,
        localeReports: List<LocaleReport>
    ): File {
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("Errors: ${errors.size}")
                errors.forEach { error -> appendLine("  $error") }
                absentLocales.sorted().forEach { locale ->
                    appendLine()
                    appendLine("$locale: no strings.xml at all")
                }
                localeReports.forEach { localeReport ->
                    appendLine()
                    appendLine("${localeReport.locale}: ${localeReport.total} warning(s)")
                    localeReport.absent.forEach { name -> appendLine("  absent: $name") }
                    localeReport.unknown.forEach { name -> appendLine("  unknown: $name") }
                    localeReport.english.forEach { name -> appendLine("  still English: $name") }
                    localeReport.other.forEach { note -> appendLine("  $note") }
                }
            }
        )
        return report
    }

    /** The drift that one locale shows against the base locale. */
    private class LocaleReport(
        val locale: String,
        /** The names that the base locale has and this locale does not have. */
        val absent: List<String>,
        /** The names that this locale has and the base locale does not have. */
        val unknown: List<String>,
        /** The names whose value is still the English text. */
        val english: List<String>,
        /** Every other warning, already written as a sentence. */
        val other: List<String>
    ) {
        val total: Int get() = absent.size + unknown.size + english.size + other.size
    }
}
