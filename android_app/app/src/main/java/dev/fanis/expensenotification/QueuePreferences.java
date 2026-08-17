package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;

/** Review-queue view preferences. */
final class QueuePreferences {
    private static final String PREFS = "queue_preferences";
    private static final String KEY_ONLY_UNPROCESSED = "only_unprocessed";

    private QueuePreferences() {
    }

    /** When true the queue hides processed and skipped candidates. */
    static boolean onlyUnprocessed(Context context) {
        return prefs(context).getBoolean(KEY_ONLY_UNPROCESSED, false);
    }

    static void setOnlyUnprocessed(Context context, boolean onlyUnprocessed) {
        prefs(context).edit().putBoolean(KEY_ONLY_UNPROCESSED, onlyUnprocessed).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
