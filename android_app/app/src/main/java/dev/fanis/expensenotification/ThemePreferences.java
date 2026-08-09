package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Stores the user's theme choice: follow the system, or force light/dark. */
final class ThemePreferences {
    static final String MODE_AUTO = "AUTO";
    static final String MODE_LIGHT = "LIGHT";
    static final String MODE_DARK = "DARK";

    private static final String PREFS = "theme_preferences";
    private static final String KEY_MODE = "mode";

    private ThemePreferences() {
    }

    static String mode(Context context) {
        String value = prefs(context).getString(KEY_MODE, MODE_AUTO);
        if (MODE_LIGHT.equals(value) || MODE_DARK.equals(value)) {
            return value;
        }
        return MODE_AUTO;
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    static String label(String mode) {
        if (MODE_LIGHT.equals(mode)) {
            return "Light";
        }
        if (MODE_DARK.equals(mode)) {
            return "Dark";
        }
        return "Auto (follow system)";
    }

    /** The UI_MODE_NIGHT_* bits the given mode forces, or 0 when following the system. */
    static int forcedNightBits(String mode) {
        if (MODE_LIGHT.equals(mode)) {
            return Configuration.UI_MODE_NIGHT_NO;
        }
        if (MODE_DARK.equals(mode)) {
            return Configuration.UI_MODE_NIGHT_YES;
        }
        return 0;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
