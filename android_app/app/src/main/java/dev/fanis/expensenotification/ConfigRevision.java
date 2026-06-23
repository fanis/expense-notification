package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;

final class ConfigRevision {
    private static final String PREFS = "config";
    private static final String KEY_REVISION = "revision";

    private ConfigRevision() {
    }

    static long current(Context context) {
        if (context == null) {
            return 0L;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REVISION, 0L);
    }

    static void bump(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1L).commit();
    }
}
