package com.aegis.fdx;

import com.aegis.fdx.ui.I18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates internationalization resource bundles, fallback behavior, parameter substitution,
 * and key set equality across all supported locales (EN, NL, DE, FR, ES).
 */
public class I18nTest {

    @Test
    @DisplayName("Every supported locale has complete key parity with the English source bundle")
    void keySetParityAcrossLocales() {
        ResourceBundle en = ResourceBundle.getBundle("com.aegis.fdx.i18n.ui", Locale.ENGLISH);
        Set<String> enKeys = new HashSet<>();
        Enumeration<String> enEnum = en.getKeys();
        while (enEnum.hasMoreElements()) {
            enKeys.add(enEnum.nextElement());
        }
        assertFalse(enKeys.isEmpty(), "English bundle must not be empty");

        Locale[] targetLocales = new Locale[] {
                Locale.forLanguageTag("nl"),
                Locale.GERMAN,
                Locale.FRENCH,
                Locale.forLanguageTag("es")
        };

        for (Locale loc : targetLocales) {
            ResourceBundle b = ResourceBundle.getBundle("com.aegis.fdx.i18n.ui", loc);
            assertNotNull(b, "Bundle must exist for " + loc);
            Set<String> locKeys = new HashSet<>();
            Enumeration<String> locEnum = b.getKeys();
            while (locEnum.hasMoreElements()) {
                locKeys.add(locEnum.nextElement());
            }

            for (String key : enKeys) {
                assertTrue(locKeys.contains(key), "Locale " + loc + " is missing key: " + key);
                String val = b.getString(key);
                assertNotNull(val, "Value for " + key + " in " + loc + " must not be null");
                assertFalse(val.isBlank(), "Value for " + key + " in " + loc + " must not be blank");
            }
        }
    }

    @Test
    @DisplayName("Translation and parameter formatting work across active locale switching")
    void translationAndFormatting() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Files", I18n.t("label.files"));
        assertEquals("Page 2 of 5", I18n.t("label.page", 2, 5));

        // Switch to Dutch
        I18n.setLocale(Locale.forLanguageTag("nl"));
        assertEquals("Bestanden", I18n.t("label.files"));
        assertEquals("Pagina 2 van 5", I18n.t("label.page", 2, 5));
        assertEquals("Bron", I18n.t("term.source"));
        assertEquals("Sleutelzin", I18n.t("term.keyword"));

        // Switch to German
        I18n.setLocale(Locale.GERMAN);
        assertEquals("Dateien", I18n.t("label.files"));
        assertEquals("Seite 2 von 5", I18n.t("label.page", 2, 5));
        assertEquals("Quelle", I18n.t("term.source"));
        assertEquals("Schlüsselphrase", I18n.t("term.keyword"));

        // Switch back to English
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Files", I18n.t("label.files"));
    }

    @Test
    @DisplayName("Missing keys fall back cleanly without throwing exceptions")
    void fallbackGraceful() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("non.existent.key", I18n.t("non.existent.key"));
        assertEquals("", I18n.t(null));
        assertEquals("", I18n.t(""));
    }

    @Test
    @DisplayName("Locale-sensitive byte, number, and date formatters")
    void formattingUtilities() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("1.5 KB", I18n.formatBytes(1536));
        assertEquals("1,000,000", I18n.formatNumber(1000000));
        assertNotNull(I18n.formatDate(LocalDate.of(2026, 9, 8)));
    }
}
