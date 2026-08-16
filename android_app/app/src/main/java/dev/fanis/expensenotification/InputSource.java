package dev.fanis.expensenotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One input config file: how to recognize its notifications and the rules to parse them. */
final class InputSource {
    final String id;
    final String displayName;
    final boolean enabled;
    final int priority;
    final Set<String> packages;
    final Set<String> senders;
    final List<InputRule> rules;

    private InputSource(String id, String displayName, boolean enabled, int priority, Set<String> packages,
                        Set<String> senders, List<InputRule> rules) {
        this.id = id;
        this.displayName = displayName;
        this.enabled = enabled;
        this.priority = priority;
        this.packages = packages;
        this.senders = senders;
        this.rules = rules;
    }

    static InputSource fromJson(String fileName, JSONObject json) {
        JSONObject match = json.optJSONObject("match");
        List<String> transforms = JsonValues.strings(json.optJSONArray("transforms"), Collections.emptyList());
        List<InputRule> rules = new ArrayList<>();
        JSONArray rawRules = json.optJSONArray("rules");
        if (rawRules != null) {
            for (int i = 0; i < rawRules.length(); i++) {
                JSONObject rawRule = rawRules.optJSONObject(i);
                if (rawRule != null) {
                    rules.add(InputRule.fromJson(rawRule, transforms));
                }
            }
        }
        return new InputSource(
                json.optString("id", stripJson(fileName)),
                json.optString("displayName", stripJson(fileName)),
                json.optBoolean("enabled", true),
                json.optInt("priority", 100),
                JsonValues.stringSet(match == null ? null : match.optJSONArray("packages"), Collections.emptySet()),
                JsonValues.lowerSet(JsonValues.strings(match == null ? null : match.optJSONArray("senders"), Collections.emptyList())),
                rules);
    }

    boolean matches(String packageName, String title, Set<String> smsPackages) {
        String safePackage = packageName == null ? "" : packageName;
        if (packages.contains(safePackage)) {
            // A source that names both packages and senders (e.g. PayPal receipts
            // arriving through the Gmail package) only claims notifications whose
            // title is one of those senders, so the rest of the email app's
            // notifications stay unwatched.
            return senders.isEmpty() || senderMatches(title);
        }
        if (!senders.isEmpty() && smsPackages.contains(safePackage)) {
            return senderMatches(title);
        }
        return packages.isEmpty() && senders.isEmpty();
    }

    private boolean senderMatches(String title) {
        String lowerTitle = (title == null ? "" : title).trim().toLowerCase(Locale.ROOT);
        return senders.contains(lowerTitle);
    }

    private static String stripJson(String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
