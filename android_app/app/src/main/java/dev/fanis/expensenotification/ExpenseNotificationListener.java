package dev.fanis.expensenotification;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;

public class ExpenseNotificationListener extends NotificationListenerService {
    private static WeakReference<ExpenseNotificationListener> instance = new WeakReference<>(null);

    public static int scanActive(Context context) {
        ExpenseNotificationListener listener = instance.get();
        if (listener == null) {
            return -1;
        }
        return listener.scanActiveNotifications(context);
    }

    public static boolean isConnected() {
        return instance.get() != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private boolean isWatched(String packageName, String title) {
        return ExpenseParser.isWatched(this, packageName, title);
    }

    @Override
    public void onListenerConnected() {
        instance = new WeakReference<>(this);
        diagnostics(this).edit()
                .putLong("last_connected_at", System.currentTimeMillis())
                .apply();
        scanActiveNotifications(this);
    }

    @Override
    public void onListenerDisconnected() {
        instance = new WeakReference<>(null);
        diagnostics(this).edit()
                .putLong("last_disconnected_at", System.currentTimeMillis())
                .apply();
        // The system can unbind the listener (low memory, app/OS update). Without an
        // explicit rebind request it stays disconnected until the app is reopened or
        // the device reboots, silently dropping every payment notification meanwhile.
        // This only fires on an orderly unbind; a hard process kill skips it, so the
        // periodic ListenerWatchdogJob re-requests the rebind independently.
        requestRebind(new ComponentName(this, ExpenseNotificationListener.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        saveCandidateIfRelevant(this, sbn);
    }

    private int scanActiveNotifications(Context context) {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) {
            diagnostics(context).edit()
                    .putLong("last_scan_at", System.currentTimeMillis())
                    .putInt("last_scan_total", 0)
                    .putInt("last_scan_watched", 0)
                    .putInt("last_scan_saved", 0)
                    .apply();
            return 0;
        }
        int saved = 0;
        int watched = 0;
        for (StatusBarNotification sbn : active) {
            if (sbn != null && isWatched(sbn.getPackageName(), titleOf(sbn))) {
                watched++;
            }
            if (saveCandidateIfRelevant(context, sbn)) {
                saved++;
            }
        }
        diagnostics(context).edit()
                .putLong("last_scan_at", System.currentTimeMillis())
                .putInt("last_scan_total", active.length)
                .putInt("last_scan_watched", watched)
                .putInt("last_scan_saved", saved)
                .apply();
        return saved;
    }

    private boolean saveCandidateIfRelevant(Context context, StatusBarNotification sbn) {
        if (sbn == null) {
            return false;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return false;
        }

        Bundle extras = notification.extras;
        String title = text(extras, Notification.EXTRA_TITLE);
        if (!isWatched(sbn.getPackageName(), title)) {
            return false;
        }
        String text = text(extras, Notification.EXTRA_TEXT);
        String bigText = text(extras, Notification.EXTRA_BIG_TEXT);
        String subText = text(extras, Notification.EXTRA_SUB_TEXT);
        String tag = sbn.getTag();
        String ticker = notification.tickerText == null ? "" : notification.tickerText.toString();
        String body = join(text, bigText, subText, ticker, tag);
        diagnostics(context).edit()
                .putLong("last_watched_at", System.currentTimeMillis())
                .putString("last_watched_package", sbn.getPackageName())
                .putString("last_watched_title", title)
                .putString("last_watched_body", body)
                .apply();

        Candidate candidate = ExpenseParser.parse(
                context,
                sbn.getPackageName(),
                appName(context, sbn.getPackageName()),
                dedupeKey(sbn, body),
                sbn.getPostTime(),
                title.isEmpty() ? firstNonEmpty(ticker, tag) : title,
                body);
        if (candidate == null) {
            diagnostics(context).edit()
                    .putString("last_result", "Parser rejected watched notification")
                    .apply();
            return false;
        }

        long inserted = new CandidateDb(context).insertIfNew(candidate);
        diagnostics(context).edit()
                .putString("last_result", inserted == -1 ? "Duplicate candidate ignored" : "Candidate saved")
                .apply();
        return inserted != -1;
    }

    // Identity used to dedupe captures (stored in the candidates table's UNIQUE
    // notification_key). sbn.getKey() alone is not enough: messaging apps (e.g. Textra)
    // post every SMS from one sender under a single conversation notification, reusing
    // the same key for every bank SMS, so all but the first were silently dropped on the
    // UNIQUE constraint. Folding the message body into the key makes each distinct SMS a
    // separate candidate, while re-scanning the same still-active notification (same key
    // + same body) still dedupes. Per-notification sources like Revolut keep a unique
    // sbn.getKey() per transaction, so two identical charges remain two candidates.
    private static String dedupeKey(StatusBarNotification sbn, String body) {
        return sbn.getKey() + "#" + Integer.toHexString((body == null ? "" : body).hashCode());
    }

    private static String titleOf(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return "";
        }
        return text(notification.extras, Notification.EXTRA_TITLE);
    }

    private static String text(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private static String firstNonEmpty(String... parts) {
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                return part.trim();
            }
        }
        return "";
    }

    private static String appName(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static SharedPreferences diagnostics(Context context) {
        return context.getSharedPreferences("diagnostics", MODE_PRIVATE);
    }
}
