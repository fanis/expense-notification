package dev.fanis.expensenotification;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// Learned mapping from a raw notification merchant string to the payee the user
// actually selected in Expense Manager. Lets recurring merchants prefill the
// correct saved payee instead of the bank's messy name. Stored as a simple
// normalizedKey -> payee map in SharedPreferences.
final class PayeeAliases {
    private static final String STORE = "payee_aliases";
    // Merchants the user never wants auto-mapped (e.g. Wolt, Bolt: the payment
    // notification names the platform, but the real payee is a different
    // restaurant each time, so a learned alias would always be wrong). Stored as
    // normalizedKey -> "1".
    private static final String BLACKLIST_STORE = "payee_blacklist";

    private PayeeAliases() {
    }

    // Collapses card-network noise so different receipts from the same merchant
    // share one key: drops any processor prefix before '*' (e.g. "SQ *", "PAYPAL *"),
    // strips digits/punctuation (store numbers, masked PANs), keeps letters of any
    // script (Latin and Greek), and folds whitespace/case.
    static String normalize(String merchant) {
        if (merchant == null) {
            return "";
        }
        String s = merchant.toUpperCase(Locale.ROOT);
        int star = s.lastIndexOf('*');
        if (star >= 0 && star < s.length() - 1) {
            s = s.substring(star + 1);
        }
        s = s.replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    // The payee to prefill for this merchant: the learned alias if one exists,
    // otherwise the raw merchant unchanged.
    static String resolve(Context context, String merchant) {
        String alias = lookup(context, merchant);
        return alias != null ? alias : merchant;
    }

    static String lookup(Context context, String merchant) {
        String key = normalize(merchant);
        if (key.isEmpty() || isBlacklistedKey(context, key)) {
            return null;
        }
        String value = store(context).getString(key, "");
        return value.isEmpty() ? null : value;
    }

    // Records merchant -> payee, but only when the payee is a real, distinct value:
    // empty payees and payees identical to the raw merchant teach nothing.
    static void learn(Context context, String merchant, String payee) {
        String key = normalize(merchant);
        if (key.isEmpty() || payee == null || isBlacklistedKey(context, key)) {
            return;
        }
        String trimmed = payee.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(merchant == null ? "" : merchant.trim())) {
            return;
        }
        store(context).edit().putString(key, trimmed).apply();
    }

    // Manually create or update an alias from the editor screen. Normalizes the
    // merchant into the storage key (so it matches future lookups) and stores the
    // trimmed payee verbatim. Unlike learn(), it allows any non-empty payee.
    // Returns the key used, or "" when either field is blank.
    static String set(Context context, String merchant, String payee) {
        String key = normalize(merchant);
        String trimmed = payee == null ? "" : payee.trim();
        if (key.isEmpty() || trimmed.isEmpty()) {
            return "";
        }
        store(context).edit().putString(key, trimmed).apply();
        return key;
    }

    static Map<String, String> all(Context context) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, ?> entry : store(context).getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), (String) value);
            }
        }
        return result;
    }

    static List<String> keys(Context context) {
        return new ArrayList<>(all(context).keySet());
    }

    static void remove(Context context, String key) {
        store(context).edit().remove(key).apply();
    }

    static int count(Context context) {
        return all(context).size();
    }

    // ---- Blacklist: merchants that must never be auto-learned or auto-mapped. ----

    static boolean isBlacklisted(Context context, String merchant) {
        return isBlacklistedKey(context, normalize(merchant));
    }

    private static boolean isBlacklistedKey(Context context, String key) {
        return !key.isEmpty() && blacklistStore(context).contains(key);
    }

    // Adds a merchant to the blacklist (and drops any learned alias for it, since a
    // blacklisted merchant should resolve to nothing). Returns the normalized key,
    // or "" when the merchant is blank.
    static String blacklist(Context context, String merchant) {
        String key = normalize(merchant);
        if (key.isEmpty()) {
            return "";
        }
        blacklistStore(context).edit().putString(key, "1").apply();
        store(context).edit().remove(key).apply();
        return key;
    }

    static void unblacklist(Context context, String key) {
        blacklistStore(context).edit().remove(key).apply();
    }

    static List<String> blacklistKeys(Context context) {
        List<String> keys = new ArrayList<>();
        for (String key : blacklistStore(context).getAll().keySet()) {
            keys.add(key);
        }
        java.util.Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    static int blacklistCount(Context context) {
        return blacklistStore(context).getAll().size();
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences blacklistStore(Context context) {
        return context.getSharedPreferences(BLACKLIST_STORE, Context.MODE_PRIVATE);
    }
}
