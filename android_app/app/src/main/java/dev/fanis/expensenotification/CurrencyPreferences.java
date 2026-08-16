package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class CurrencyPreferences {
    static final String MODE_ACCOUNT = "ACCOUNT";
    static final String MODE_ORIGINAL = "ORIGINAL";
    static final String MODE_ASK = "ASK";

    private static final String PREFS = "currency_preferences";
    private static final String KEY_MODE = "mode";
    private static final String KEY_HOME_CURRENCY = "home_currency";

    private CurrencyPreferences() {
    }

    static String mode(Context context) {
        String value = prefs(context).getString(KEY_MODE, MODE_ACCOUNT);
        if (MODE_ORIGINAL.equals(value) || MODE_ASK.equals(value)) {
            return value;
        }
        return MODE_ACCOUNT;
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    /** The user's home (book) currency as an ISO code, or "" when not configured. */
    static String homeCurrency(Context context) {
        return normalizeHomeCurrency(prefs(context).getString(KEY_HOME_CURRENCY, ""));
    }

    static void setHomeCurrency(Context context, String currency) {
        prefs(context).edit().putString(KEY_HOME_CURRENCY, normalizeHomeCurrency(currency)).apply();
    }

    /** Uppercases a 3-letter code; anything else (including empty) means "not set". */
    static String normalizeHomeCurrency(String currency) {
        String cleaned = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        return cleaned.matches("[A-Z]{3}") ? cleaned : "";
    }

    /** True when a captured currency should be flagged as foreign to the home currency. */
    static boolean isForeign(Context context, String currency) {
        String home = homeCurrency(context);
        return !home.isEmpty() && currency != null && !currency.trim().isEmpty()
                && !home.equalsIgnoreCase(currency.trim());
    }

    static String label(String mode) {
        if (MODE_ORIGINAL.equals(mode)) {
            return "Charged currency";
        }
        if (MODE_ASK.equals(mode)) {
            return "Ask each time";
        }
        return "Account currency";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
