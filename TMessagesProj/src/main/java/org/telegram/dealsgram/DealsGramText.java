package org.telegram.dealsgram;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * DealsGram texts, in the language chosen in Telegram (not the system one).
 *
 * The texts are the keys DealsGram_<name> in TMessagesProj_AppStandalone/src/main/res/values{,-it,-de,-fr,-es,-pt-rBR}/
 * strings.xml: Telegram's build turns every values-xx/strings.xml into a localization asset and LocaleController
 * picks it from the current Telegram language, falling back to English (values/). They are read by name because
 * this code lives in TMessagesProj and cannot see the app module's R.string. See docs/modifiche-a-telegram.md.
 */
public final class DealsGramText {

    private static final String PREFIX = "DealsGram_";

    private DealsGramText() {
    }

    public static String get(String name) {
        return LocaleController.getString(PREFIX + name);
    }

    @SuppressWarnings("deprecation")
    public static String format(String name, Object... args) {
        return LocaleController.formatString(PREFIX + name, 0, args);
    }

    /** Plural forms DealsGram_<name>_one, _other, ... with Telegram's rules; the count is the first argument (%1$d). */
    public static String plural(String name, int count, Object... args) {
        return LocaleController.formatPluralString(PREFIX + name, count, args);
    }

    /** The Telegram language, for dates and numbers. */
    public static Locale locale() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null ? locale : Locale.getDefault();
    }
}
