package dev.fanis.expensenotification;

import android.app.Notification;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExpenseNotificationListener extends NotificationListenerService {
    private static WeakReference<ExpenseNotificationListener> instance = new WeakReference<>(null);
    // Keeps parsing and database writes off the process main thread, which also
    // serializes them so a burst of notifications can't interleave.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

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

    private boolean isWatched(String packageName, String title) {
        return ExpenseParser.isWatched(this, packageName, title);
    }

    @Override
    public void onListenerConnected() {
        instance = new WeakReference<>(this);
        diagnostics(this).edit()
                .putLong("last_connected_at", System.currentTimeMillis())
                .apply();
        WORKER.execute(() -> scanActiveNotifications(this));
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
        WORKER.execute(() -> saveCandidateIfRelevant(this, sbn));
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
        List<StackedMessage> stacked = stackedMessages(extras);
        // A stacked summary (e.g. an email digest titled "2 new messages") carries the
        // watched sender inside its entries rather than in the title.
        if (!isWatched(sbn.getPackageName(), title) && !anyStackedMessageWatched(context, sbn.getPackageName(), stacked)) {
            return false;
        }
        String text = text(extras, Notification.EXTRA_TEXT);
        String bigText = text(extras, Notification.EXTRA_BIG_TEXT);
        String subText = text(extras, Notification.EXTRA_SUB_TEXT);
        String tag = sbn.getTag();
        String ticker = notification.tickerText == null ? "" : notification.tickerText.toString();
        String body = join(text, bigText, subText, ticker, tag);

        SharedPreferences.Editor diagnostics = diagnostics(context).edit()
                .putLong("last_watched_at", System.currentTimeMillis())
                .putString("last_watched_package", sbn.getPackageName())
                .putString("last_watched_title", title)
                .putString("last_watched_body", body);

        int saved = capture(context, sbn.getPackageName(), appName(context, sbn.getPackageName()), sbn.getKey(),
                sbn.getPostTime(), title.isEmpty() ? firstNonEmpty(ticker, tag) : title, body, stacked);
        diagnostics.putString("last_result", saved > 0
                ? "Saved " + saved + " candidate(s)"
                : "No new candidate (parser rejected, or duplicate)").apply();
        return saved > 0;
    }

    /** One recoverable entry of a stacked summary: an InboxStyle line or a MessagingStyle message. */
    static final class StackedMessage {
        final String sender; // "" when the summary format carries no per-entry sender
        final String text;
        final long time;     // 0 when the entry carries no timestamp

        StackedMessage(String sender, String text, long time) {
            this.sender = sender;
            this.text = text;
            this.time = time;
        }
    }

    /** Collects the per-message entries of a stacked notification, oldest first. */
    static List<StackedMessage> stackedMessages(Bundle extras) {
        ArrayList<StackedMessage> items = new ArrayList<>();
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                String text = line == null ? "" : line.toString().trim();
                if (!text.isEmpty()) {
                    items.add(new StackedMessage("", text, 0L));
                }
            }
        }
        // MessagingStyle (modern SMS apps) keeps its conversation history as an array
        // of bundles carrying "text"/"sender"/"time"; reading EXTRA_MESSAGES directly
        // is the listener-side way to get at them.
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            for (Parcelable parcelable : messages) {
                if (!(parcelable instanceof Bundle)) {
                    continue;
                }
                Bundle message = (Bundle) parcelable;
                CharSequence text = message.getCharSequence("text");
                String cleanText = text == null ? "" : text.toString().trim();
                if (cleanText.isEmpty()) {
                    continue;
                }
                CharSequence sender = message.getCharSequence("sender");
                if (sender == null && Build.VERSION.SDK_INT >= 28) {
                    Object person = message.get("sender_person");
                    if (person instanceof Person) {
                        sender = ((Person) person).getName();
                    }
                }
                items.add(new StackedMessage(sender == null ? "" : sender.toString().trim(),
                        cleanText, message.getLong("time", 0L)));
            }
        }
        return items;
    }

    /**
     * Parses the notification body plus, for stacked summaries, each expanded entry,
     * and stores whatever is new. Every entry is keyed by its own content, so
     * messages that were missed while the listener was down are recovered from the
     * summary's history without duplicating the ones already captured; the newest
     * entry is normally also the notification body and is skipped in the loop because
     * the body parse above already covers it.
     */
    static int capture(Context context, String packageName, String appName, String sbnKey, long postedAt,
                       String title, String body, List<StackedMessage> stacked) {
        CandidateDb db = CandidateDb.getInstance(context);
        int saved = 0;
        Candidate candidate = ExpenseParser.parse(
                context, packageName, appName, dedupeKey(sbnKey, body), postedAt, title, body);
        if (candidate != null && db.insertIfNew(candidate) != -1) {
            saved++;
        }
        if (stacked == null) {
            return saved;
        }
        String safeBody = body == null ? "" : body;
        for (StackedMessage message : stacked) {
            String line = message.text;
            if (line.isEmpty() || safeBody.contains(line)) {
                continue;
            }
            String lineKey = dedupeKey(sbnKey, line);
            // A MessagingStyle timestamp dates the recovered message to when it
            // actually arrived, not to when the summary was re-posted.
            long at = message.time > 0 ? message.time : postedAt;
            Candidate fromLine = message.sender.isEmpty()
                    ? null
                    : ExpenseParser.parse(context, packageName, appName, lineKey, at, message.sender, line);
            if (fromLine == null) {
                fromLine = ExpenseParser.parse(context, packageName, appName, lineKey, at, title, line);
            }
            if (fromLine == null) {
                // Email digests put the sender inside the line ("Sender Subject...");
                // retry with the line's own sender as the title.
                String[] split = NotificationText.splitStackedLine(line);
                if (split != null) {
                    fromLine = ExpenseParser.parse(context, packageName, appName, lineKey, at, split[0], split[1]);
                }
            }
            // The same payment can already be in the queue through its own notification,
            // whose body text (and so its dedupe key) differs from the summary entry;
            // recovery from summaries is best-effort and must never double-capture.
            if (fromLine != null && !db.hasEquivalent(fromLine) && db.insertIfNew(fromLine) != -1) {
                saved++;
            }
        }
        return saved;
    }

    private static boolean anyStackedMessageWatched(Context context, String packageName, List<StackedMessage> stacked) {
        if (stacked == null) {
            return false;
        }
        for (StackedMessage message : stacked) {
            if (!message.sender.isEmpty() && ExpenseParser.isWatched(context, packageName, message.sender)) {
                return true;
            }
            String[] split = NotificationText.splitStackedLine(message.text);
            if (split != null && ExpenseParser.isWatched(context, packageName, split[0])) {
                return true;
            }
        }
        return false;
    }

    // Identity used to dedupe captures (stored in the candidates table's UNIQUE
    // notification_key). sbn.getKey() alone is not enough: messaging apps (e.g. Textra)
    // post every SMS from one sender under a single conversation notification, reusing
    // the same key for every bank SMS, so all but the first were silently dropped on the
    // UNIQUE constraint. Folding the message body into the key makes each distinct SMS a
    // separate candidate, while re-scanning the same still-active notification (same key
    // + same body) still dedupes. Per-notification sources like Revolut keep a unique
    // sbn.getKey() per transaction, so two identical charges remain two candidates.
    // SHA-256 (not String.hashCode) so two different SMS can't collide into one key
    // and silently drop an expense.
    static String dedupeKey(String sbnKey, String body) {
        return sbnKey + "#" + sha256(body == null ? "" : body);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Android ships SHA-256; keep a deterministic fallback anyway.
            return Integer.toHexString(text.hashCode());
        }
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
