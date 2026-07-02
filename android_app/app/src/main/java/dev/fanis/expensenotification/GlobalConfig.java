package dev.fanis.expensenotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser-wide settings: which SMS apps carry bank senders, phrases that mark a
 * notification as not-a-charge, and whether zero amounts are dropped. Loaded from
 * global.json (asset or user override); the built-in defaults are the safety net.
 */
final class GlobalConfig {
    // Notifications that look payment-shaped but are not a completed charge: 3DS
    // approval prompts (the duplicate that precedes a real "successful" message),
    // declines, and card verification/registration. Matched as whole phrases so they
    // don't catch a genuine payment whose merchant name happens to contain a word.
    private static final String[] REJECT_PHRASES = {
            "waiting for your approval",
            "verify a payment",
            "verify your payment",
            "tap to start",
            "declined",
            "insufficient balance",
            "insufficient funds",
            "verified your card",
            "haven't been charged",
            "have not been charged",
            "card registration",
            "card verification",
    };

    private static final String[] SMS_PACKAGES = {
            "com.textra", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
    };

    final Set<String> smsPackages;
    final List<String> rejectPhrases;
    final boolean dropZeroAmount;

    private GlobalConfig(Set<String> smsPackages, List<String> rejectPhrases, boolean dropZeroAmount) {
        this.smsPackages = smsPackages;
        this.rejectPhrases = rejectPhrases;
        this.dropZeroAmount = dropZeroAmount;
    }

    static GlobalConfig defaults() {
        return new GlobalConfig(
                new HashSet<>(Arrays.asList(SMS_PACKAGES)),
                new ArrayList<>(Arrays.asList(REJECT_PHRASES)),
                true);
    }

    /** The defaults as JSON, for callers that need a global config document. */
    static String defaultJson() {
        try {
            GlobalConfig defaults = defaults();
            return new JSONObject()
                    .put("smsPackages", new JSONArray(new ArrayList<>(defaults.smsPackages)))
                    .put("dropZeroAmount", defaults.dropZeroAmount)
                    .put("rejectPhrases", new JSONArray(defaults.rejectPhrases))
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    static GlobalConfig fromJson(JSONObject json) {
        GlobalConfig defaults = defaults();
        return new GlobalConfig(
                JsonValues.stringSet(json.optJSONArray("smsPackages"), defaults.smsPackages),
                JsonValues.strings(json.optJSONArray("rejectPhrases"), defaults.rejectPhrases),
                json.optBoolean("dropZeroAmount", true));
    }

    GlobalConfig withSmsPackages(Set<String> extraSmsPackages) {
        if (extraSmsPackages == null || extraSmsPackages.isEmpty()) {
            return this;
        }
        HashSet<String> merged = new HashSet<>(smsPackages);
        merged.addAll(extraSmsPackages);
        return new GlobalConfig(merged, rejectPhrases, dropZeroAmount);
    }
}
