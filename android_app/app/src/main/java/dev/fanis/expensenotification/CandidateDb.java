package dev.fanis.expensenotification;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class CandidateDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "expense_candidates.db";
    private static final int DB_VERSION = 4;
    // Settled (processed/skipped) candidates older than this are pruned so the
    // database and the review screen don't grow forever. NEW candidates are kept
    // indefinitely: they still need the user's decision.
    static final long SETTLED_RETENTION_MS = 90L * 24 * 60 * 60 * 1000;

    private static CandidateDb instance;

    private final Context appContext;

    static synchronized CandidateDb getInstance(Context context) {
        if (instance == null) {
            instance = new CandidateDb(context.getApplicationContext());
        }
        return instance;
    }

    /** Tests run against per-test data directories; lets them drop the cached helper. */
    static synchronized void resetInstanceForTesting() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private CandidateDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE candidates (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "notification_key TEXT NOT NULL UNIQUE," +
                "package_name TEXT NOT NULL," +
                "app_name TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "merchant TEXT NOT NULL," +
                "amount TEXT NOT NULL," +
                "currency TEXT NOT NULL," +
                "original_amount TEXT NOT NULL DEFAULT ''," +
                "original_currency TEXT NOT NULL DEFAULT ''," +
                "suggested_category TEXT NOT NULL," +
                "suggested_payment_method TEXT NOT NULL," +
                "note TEXT NOT NULL DEFAULT ''," +
                "transaction_type TEXT NOT NULL DEFAULT 'EXPENSE'," +
                "posted_at INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'NEW'," +
                "created_at INTEGER NOT NULL," +
                "parsed_revision INTEGER NOT NULL DEFAULT -1)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE candidates ADD COLUMN original_amount TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE candidates ADD COLUMN original_currency TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE candidates ADD COLUMN note TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE candidates ADD COLUMN transaction_type TEXT NOT NULL DEFAULT 'EXPENSE'");
        }
        if (oldVersion < 4) {
            // -1 marks rows parsed by an unknown (pre-column) config; they reparse once.
            db.execSQL("ALTER TABLE candidates ADD COLUMN parsed_revision INTEGER NOT NULL DEFAULT -1");
        }
    }

    long insertIfNew(Candidate candidate) {
        pruneSettled();
        ContentValues values = new ContentValues();
        values.put("notification_key", candidate.notificationKey);
        values.put("package_name", candidate.packageName);
        values.put("app_name", candidate.appName);
        values.put("title", value(candidate.title));
        values.put("body", value(candidate.text));
        values.put("merchant", value(candidate.merchant));
        values.put("amount", value(candidate.amount));
        values.put("currency", value(candidate.currency));
        values.put("original_amount", value(candidate.originalAmount));
        values.put("original_currency", value(candidate.originalCurrency));
        values.put("suggested_category", value(candidate.suggestedCategory));
        values.put("suggested_payment_method", value(candidate.suggestedPaymentMethod));
        values.put("note", value(candidate.note));
        values.put("transaction_type", value(candidate.transactionType, Candidate.TYPE_EXPENSE));
        values.put("posted_at", candidate.postedAt);
        values.put("status", value(candidate.status, Candidate.STATUS_NEW));
        values.put("created_at", System.currentTimeMillis());
        values.put("parsed_revision", ConfigRevision.current(appContext));
        return getWritableDatabase().insertWithOnConflict("candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    List<Candidate> listAll() {
        ArrayList<Candidate> items = new ArrayList<>();
        ArrayList<Candidate> stale = new ArrayList<>();
        long currentRevision = ConfigRevision.current(appContext);
        try (Cursor cursor = getReadableDatabase().query(
                "candidates",
                null,
                null,
                null,
                null,
                null,
                "posted_at DESC, id DESC")) {
            int revisionIndex = cursor.getColumnIndex("parsed_revision");
            while (cursor.moveToNext()) {
                Candidate candidate = fromCursor(cursor);
                items.add(candidate);
                long parsedRevision = revisionIndex < 0 ? -1 : cursor.getLong(revisionIndex);
                if (parsedRevision != currentRevision) {
                    stale.add(candidate);
                }
            }
        }
        if (!stale.isEmpty()) {
            reparseStale(stale, currentRevision);
        }
        return items;
    }

    int mark(long id, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        return getWritableDatabase().update("candidates", values, "id = ?", new String[]{String.valueOf(id)});
    }

    int deleteAll() {
        return getWritableDatabase().delete("candidates", null, null);
    }

    int pruneSettled() {
        long cutoff = System.currentTimeMillis() - SETTLED_RETENTION_MS;
        return getWritableDatabase().delete(
                "candidates",
                "status != ? AND posted_at < ?",
                new String[]{Candidate.STATUS_NEW, String.valueOf(cutoff)});
    }

    // Re-derives parsed fields (payee, payment method, note, category, amount,
    // transaction type) from the stored SMS so existing candidates reflect the
    // current parser config. Identity (id, notification key, raw title/body) and
    // workflow status are preserved. Runs only for rows whose parsed_revision is
    // behind the current config revision, and writes the result back so the work
    // happens once per config change instead of on every read.
    private void reparseStale(List<Candidate> stale, long currentRevision) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Candidate candidate : stale) {
                Candidate reparsed = ExpenseParser.parse(
                        appContext,
                        candidate.packageName, candidate.appName, candidate.notificationKey,
                        candidate.postedAt, candidate.title, candidate.text);
                ContentValues values = new ContentValues();
                if (reparsed != null) {
                    candidate.merchant = reparsed.merchant;
                    candidate.amount = reparsed.amount;
                    candidate.currency = reparsed.currency;
                    candidate.originalAmount = reparsed.originalAmount;
                    candidate.originalCurrency = reparsed.originalCurrency;
                    candidate.suggestedCategory = reparsed.suggestedCategory;
                    candidate.suggestedPaymentMethod = reparsed.suggestedPaymentMethod;
                    candidate.note = reparsed.note;
                    candidate.transactionType = reparsed.transactionType;
                    candidate.postedAt = reparsed.postedAt;
                    values.put("merchant", value(candidate.merchant));
                    values.put("amount", value(candidate.amount));
                    values.put("currency", value(candidate.currency));
                    values.put("original_amount", value(candidate.originalAmount));
                    values.put("original_currency", value(candidate.originalCurrency));
                    values.put("suggested_category", value(candidate.suggestedCategory));
                    values.put("suggested_payment_method", value(candidate.suggestedPaymentMethod));
                    values.put("note", value(candidate.note));
                    values.put("transaction_type", value(candidate.transactionType, Candidate.TYPE_EXPENSE));
                    values.put("posted_at", candidate.postedAt);
                }
                // A candidate the current config no longer matches keeps its stored
                // fields; the revision still advances so it isn't retried until the
                // config changes again.
                values.put("parsed_revision", currentRevision);
                db.update("candidates", values, "id = ?", new String[]{String.valueOf(candidate.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private Candidate fromCursor(Cursor cursor) {
        Candidate candidate = new Candidate();
        candidate.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        candidate.notificationKey = cursor.getString(cursor.getColumnIndexOrThrow("notification_key"));
        candidate.packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name"));
        candidate.appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name"));
        candidate.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        candidate.text = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        candidate.merchant = cursor.getString(cursor.getColumnIndexOrThrow("merchant"));
        candidate.amount = cursor.getString(cursor.getColumnIndexOrThrow("amount"));
        candidate.currency = cursor.getString(cursor.getColumnIndexOrThrow("currency"));
        candidate.originalAmount = optionalString(cursor, "original_amount");
        candidate.originalCurrency = optionalString(cursor, "original_currency");
        candidate.suggestedCategory = cursor.getString(cursor.getColumnIndexOrThrow("suggested_category"));
        candidate.suggestedPaymentMethod = cursor.getString(cursor.getColumnIndexOrThrow("suggested_payment_method"));
        candidate.note = optionalString(cursor, "note");
        String type = optionalString(cursor, "transaction_type");
        candidate.transactionType = type.isEmpty() ? Candidate.TYPE_EXPENSE : type;
        candidate.postedAt = cursor.getLong(cursor.getColumnIndexOrThrow("posted_at"));
        candidate.status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
        return candidate;
    }

    private static String value(String text) {
        return value(text, "");
    }

    private static String value(String text, String fallback) {
        return text == null ? fallback : text;
    }

    private static String optionalString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        return value(cursor.getString(index));
    }
}
