package com.aegis.fdx.ui;

import java.text.MessageFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Internationalization and localization management for AEGIS-FDX.
 *
 * <p>Provides access to localized interface strings while preserving domain invariants:
 * <ul>
 *   <li>Database column names, forensic constants, and Lucene fields remain strictly English.</li>
 *   <li>Term normalization ({@link com.aegis.fdx.facade.Terms#normalize}) uses {@code Locale.ROOT}
 *       and is completely independent of the UI language.</li>
 *   <li>Missing keys fall back cleanly to English, and ultimately to the key name itself,
 *       never throwing runtime exceptions.</li>
 * </ul>
 */
public final class I18n {

    private static final String BUNDLE_BASE = "com.aegis.fdx.i18n.ui";
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.ENGLISH,
            Locale.forLanguageTag("nl"),
            Locale.GERMAN,
            Locale.FRENCH,
            Locale.forLanguageTag("es")
    );

    private static final Map<String, String> LOCALE_NAMES = new LinkedHashMap<>();
    static {
        LOCALE_NAMES.put("en", "English");
        LOCALE_NAMES.put("nl", "Nederlands");
        LOCALE_NAMES.put("de", "Deutsch");
        LOCALE_NAMES.put("fr", "Français");
        LOCALE_NAMES.put("es", "Español");
    }

    private static volatile Locale currentLocale = DEFAULT_LOCALE;
    private static volatile ResourceBundle bundle = loadBundle(DEFAULT_LOCALE);
    private static final ResourceBundle fallbackBundle = loadBundle(DEFAULT_LOCALE);

    private I18n() {
    }

    private static ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE, locale,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        } catch (MissingResourceException e) {
            try {
                return ResourceBundle.getBundle(BUNDLE_BASE, locale);
            } catch (MissingResourceException e2) {
                return null;
            }
        }
    }

    /**
     * Sets the active UI locale.
     *
     * @param locale the locale to activate; if null, defaults to English
     */
    public static synchronized void setLocale(Locale locale) {
        currentLocale = locale == null ? DEFAULT_LOCALE : locale;
        ResourceBundle b = loadBundle(currentLocale);
        bundle = b != null ? b : fallbackBundle;
    }

    /** Returns the currently active UI locale. */
    public static Locale getLocale() {
        return currentLocale;
    }

    /** Returns the list of supported locales. */
    public static List<Locale> supportedLocales() {
        return SUPPORTED_LOCALES;
    }

    /** Returns the display name of a supported language tag. */
    public static String getLanguageDisplayName(String tag) {
        if (tag == null) return "English";
        return LOCALE_NAMES.getOrDefault(tag.toLowerCase(Locale.ROOT), tag);
    }

    /** Returns available language tags mapped to display names. */
    public static Map<String, String> availableLanguages() {
        return Collections.unmodifiableMap(LOCALE_NAMES);
    }

    /**
     * Translates a message key into the current active language.
     *
     * @param key the message key (e.g. "screen.keywords.title")
     * @return localized string, or English fallback, or the key itself
     */
    public static String t(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (bundle != null) {
            try {
                return bundle.getString(key);
            } catch (MissingResourceException ignored) {
            }
        }
        if (fallbackBundle != null) {
            try {
                return fallbackBundle.getString(key);
            } catch (MissingResourceException ignored) {
            }
        }
        return key;
    }

    /**
     * Translates and formats a message key with arguments.
     *
     * @param key  the message key
     * @param args formatting arguments
     * @return formatted localized string
     */
    public static String t(String key, Object... args) {
        String pattern = t(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }

    /** Locale-sensitive byte size formatting. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        NumberFormat nf = NumberFormat.getNumberInstance(currentLocale);
        nf.setMaximumFractionDigits(1);
        return nf.format(bytes / Math.pow(1024, exp)) + " " + pre;
    }

    /** Locale-sensitive integer formatting. */
    public static String formatNumber(long number) {
        return NumberFormat.getIntegerInstance(currentLocale).format(number);
    }

    /** Locale-sensitive date formatting. */
    public static String formatDate(LocalDate date) {
        if (date == null) return "—";
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale));
    }
}
