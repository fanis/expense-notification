package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;

final class CurrencyPreferences {
    static final String MODE_ACCOUNT = "ACCOUNT";
    static final String MODE_ORIGINAL = "ORIGINAL";
    static final String MODE_ASK = "ASK";

    private static final String PREFS = "currency_preferences";
    private static final String KEY_MODE = "mode";

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
