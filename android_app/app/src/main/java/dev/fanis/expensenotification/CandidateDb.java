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
    private static final int DB_VERSION = 3;
    private final Context appContext;

    CandidateDb(Context context) {
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
                "created_at INTEGER NOT NULL)");
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
    }

    long insertIfNew(Candidate candidate) {
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
        values.put("transaction_type", value(candidate.transactionType, "EXPENSE"));
        values.put("posted_at", candidate.postedAt);
        values.put("status", value(candidate.status, "NEW"));
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    List<Candidate> listNew() {
        return queryByStatus("NEW");
    }

    List<Candidate> listAll() {
        ArrayList<Candidate> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "candidates",
                null,
                null,
                null,
                null,
                null,
                "posted_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                items.add(fromCursor(cursor));
            }
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

    private List<Candidate> queryByStatus(String status) {
        ArrayList<Candidate> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "candidates",
                null,
                "status = ?",
                new String[]{status},
                null,
                null,
                "posted_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                items.add(fromCursor(cursor));
            }
        }
        return items;
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
        candidate.transactionType = type.isEmpty() ? "EXPENSE" : type;
        candidate.postedAt = cursor.getLong(cursor.getColumnIndexOrThrow("posted_at"));
        candidate.status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
        reparseFromStoredSms(candidate);
        return candidate;
    }

    // Re-derive parsed fields from the stored SMS so existing candidates reflect the
    // current parser (payee, payment method, note, category, amount, transaction type).
    // Identity (id, notification key, raw title/body) and workflow status are preserved.
    private void reparseFromStoredSms(Candidate candidate) {
        Candidate reparsed = ExpenseParser.parse(
                appContext,
                candidate.packageName, candidate.appName, candidate.notificationKey,
                candidate.postedAt, candidate.title, candidate.text);
        if (reparsed == null) {
            return;
        }
        candidate.merchant = reparsed.merchant;
        candidate.amount = reparsed.amount;
        candidate.currency = reparsed.currency;
        candidate.originalAmount = reparsed.originalAmount;
        candidate.originalCurrency = reparsed.originalCurrency;
        candidate.suggestedCategory = reparsed.suggestedCategory;
        candidate.suggestedPaymentMethod = reparsed.suggestedPaymentMethod;
        candidate.note = reparsed.note;
        candidate.transactionType = reparsed.transactionType;
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
