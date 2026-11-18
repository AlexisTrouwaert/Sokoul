package com.sokoul.pc

import com.sokoul.pc.i18n.InterfaceLanguages
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.i18n.Translations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Scenarios of openspec/changes/interface-languages/specs/interface-language. */
class TranslationsTest {
    private val before = Locale.getDefault()

    @After fun restore() {
        Locale.setDefault(before)
    }

    @Test fun anExactTextIsTranslatedAndAMissingOneStaysEnglish() {
        assertEquals("Ajustes-test", Translations.lookup("zz", "Settings"))
        assertNull(Translations.lookup("zz", "Nothing like this"))
        assertNull("English needs no catalogue", Translations.lookup("en", "Settings"))
    }

    @Test fun aTextWithValuesIsTranslatedWithTheValuesInTheirPlace() {
        assertEquals("Reproduciendo en inglés · sin audio en italiano", Translations.lookup("zz", "No italiano audio · Playing in inglés"))
        assertEquals("%s está lista", Translations.lookup("zz", "%s is ready"))
    }

    @Test fun aTemplateWithoutWordsNeverRewritesOtherTexts() {
        assertNull(Translations.lookup("zz", "Inception · 2010"))
    }

    @Test fun theSystemLanguageIsUsedWhenShippedAndEnglishOtherwise() {
        assertEquals("es", InterfaceLanguages.current(Locale.forLanguageTag("es-MX")).code)
        assertEquals("en", InterfaceLanguages.current(Locale.forLanguageTag("ja-JP")).code)
        assertEquals("ar", InterfaceLanguages.current(Locale.forLanguageTag("ar")).code)
        assertTrue(InterfaceLanguages.current(Locale.forLanguageTag("ar")).rtl)
        assertEquals("nl-NL", Strings.catalogLanguage(Locale.forLanguageTag("nl")))
        assertEquals("en-US", Strings.catalogLanguage(Locale.forLanguageTag("ja-JP")))
        assertEquals("JP", Strings.region(Locale.forLanguageTag("ja-JP")))
        assertEquals("BR", Strings.region(Locale.forLanguageTag("pt")))
    }

    @Test fun frenchKeepsItsOwnTextAndEnglishItsOwn() {
        assertEquals("Réglages", Strings.pick("Settings", "Réglages", Locale.FRENCH))
        assertEquals("Settings", Strings.pick("Settings", "Réglages", Locale.ENGLISH))
        assertEquals("Settings", Strings.pick("Settings", "Réglages", Locale.JAPANESE))
    }

    /** Every line of a shipped catalogue is keyed by a text the interface really has: none is lost to a typo. */
    @Test fun shippedCataloguesOnlyHoldKnownTexts() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/resources/i18n").isDirectory }
        val dir = File(root, "app/src/main/resources/i18n")
        // Every English key of the source, even one whose French column is empty.
        val source = File(dir, "source.tsv").readLines().filter { !it.startsWith("#") && '\t' in it }
            .map { it.substringBefore('\t').replace("\\t", "\t").replace("\\n", "\n") }.toSet()
        for (code in InterfaceLanguages.all.map { it.code } - setOf("en", "fr")) {
            val file = File(dir, "$code.tsv").takeIf { it.isFile } ?: continue
            val keys = Translations.parse(file.readLines().asSequence()).keys
            val unknown = keys - source
            assertTrue("$code: keys not in source.tsv: ${unknown.take(5)}", unknown.isEmpty())
            println("i18n $code: ${keys.size} of ${source.size} texts translated")
        }
    }
}
