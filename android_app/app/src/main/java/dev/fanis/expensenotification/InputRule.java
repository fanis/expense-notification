package dev.fanis.expensenotification;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One parsing rule inside an input source. Regex rules compile their pattern once,
 * at construction (folded to Latin lookalikes when the source uses foldGreek), so
 * matching a notification never pays a Pattern.compile.
 */
final class InputRule {
    final String name;
    final String type;
    // Compiled at construction; null for non-regex rules and for patterns that do
    // not compile (a bad user pattern makes the rule inert instead of crashing the
    // notification listener at parse time).
    private final Pattern pattern;
    private final boolean foldGreek;
    private final boolean multiCurrency;
    private final JSONObject output;
    private final String merchantSource;
    private final String noteSource;

    private InputRule(String name, String type, Pattern pattern, boolean foldGreek, boolean multiCurrency,
                      JSONObject output, String merchantSource, String noteSource) {
        this.name = name;
        this.type = type;
        this.pattern = pattern;
        this.foldGreek = foldGreek;
        this.multiCurrency = multiCurrency;
        this.output = output == null ? new JSONObject() : output;
        this.merchantSource = merchantSource;
        this.noteSource = noteSource;
    }

    static InputRule fromJson(JSONObject json, List<String> sourceTransforms) {
        List<String> transforms = JsonValues.strings(json.optJSONArray("transforms"), Collections.emptyList());
        boolean foldGreek = sourceTransforms.contains("foldGreek") || transforms.contains("foldGreek");
        boolean multiCurrency = sourceTransforms.contains("multiCurrency") || transforms.contains("multiCurrency");
        String type = json.optString("type", "regex");
        String rawPattern = json.optString("pattern", "");
        Pattern pattern = null;
        if ("regex".equals(type) && !rawPattern.isEmpty()) {
            try {
                pattern = Pattern.compile(
                        foldGreek ? ExpenseParser.foldAmbiguousGreek(rawPattern) : rawPattern,
                        JsonValues.patternFlags(json.optJSONArray("flags")));
            } catch (PatternSyntaxException ignored) {
                // Leave the rule inert; ConfigValidator reports the broken pattern.
            }
        }
        return new InputRule(
                json.optString("name", ""),
                type,
                pattern,
                foldGreek,
                multiCurrency,
                json.optJSONObject("output"),
                json.optString("merchantSource", ""),
                json.optString("noteSource", ""));
    }

    Candidate parse(GlobalConfig global, String packageName, String appName, String key, long postedAt,
                    String title, String body, NotificationText text) {
        if ("amountFallback".equals(type)) {
            return amountFallback(global, packageName, appName, key, postedAt, title, body, text.combined);
        }
        if (pattern == null) {
            return null;
        }
        return regexCandidate(global, packageName, appName, key, postedAt, title, body, text);
    }

    private Candidate amountFallback(GlobalConfig global, String packageName, String appName, String key, long postedAt,
                                     String title, String body, String combined) {
        ExpenseParser.MultiCurrencyAmount multi = multiCurrency ? ExpenseParser.extractMultiCurrencyAmount(combined) : null;
        ExpenseParser.Amount amount = multi != null ? multi.account : ExpenseParser.extractAmount(combined);
        if (amount == null) {
            return null;
        }
        if (global.dropZeroAmount && ExpenseParser.isZero(amount.value)) {
            return null;
        }
        Candidate candidate = baseCandidate(packageName, appName, key, postedAt, title, body);
        candidate.amount = amount.value;
        candidate.currency = amount.currency;
        if (multi != null) {
            candidate.originalAmount = multi.original.value;
            candidate.originalCurrency = multi.original.currency;
        }
        candidate.merchant = merchantFromSource(title, body, amount);
        candidate.note = "body".equals(noteSource) ? ExpenseParser.safe(body) : "";
        candidate.suggestedPaymentMethod = paymentMethod(combined, packageName, appName,
                output.optString("paymentMethod", "@packageDefault"), "");
        candidate.suggestedCategory = category(candidate.merchant, output.optString("category", "@keyword"));
        candidate.status = Candidate.STATUS_NEW;
        return ExpenseParser.isInteresting(candidate) ? candidate : null;
    }

    private Candidate regexCandidate(GlobalConfig global, String packageName, String appName, String key, long postedAt,
                                     String title, String body, NotificationText text) {
        // Folding is 1:1 and length-preserving, so group offsets from the folded
        // target remain valid against the original combined text.
        Matcher matcher = pattern.matcher(foldGreek ? text.folded() : text.combined);
        if (!matcher.find()) {
            return null;
        }
        String combined = text.combined;
        String amount = ExpenseParser.normalizeAmount(group(matcher, combined, "amount"));
        if (amount.isEmpty()) {
            return null;
        }
        if (global.dropZeroAmount && ExpenseParser.isZero(amount)) {
            return null;
        }
        Candidate candidate = baseCandidate(packageName, appName, key, postedAt, title, body);
        candidate.amount = amount;
        candidate.currency = valueOrGroup(matcher, combined, output.optString("currency", ""), "currency");
        String merchant = group(matcher, combined, "merchant");
        if (merchant.isEmpty()) {
            merchant = merchantFromSource(title, body, new ExpenseParser.Amount(candidate.currency, candidate.amount));
        }
        candidate.merchant = ExpenseParser.cleanupMerchant(merchant);
        candidate.note = group(matcher, combined, "note");
        if (candidate.note.isEmpty() && "body".equals(noteSource)) {
            candidate.note = ExpenseParser.safe(body);
        }
        String card = group(matcher, combined, "card");
        candidate.suggestedPaymentMethod = paymentMethod(combined, packageName, appName,
                output.optString("paymentMethod", "@packageDefault"), card);
        candidate.suggestedCategory = category(candidate.merchant, output.optString("category", "@keyword"));
        candidate.transactionType = output.optString("type", Candidate.TYPE_EXPENSE);
        if (candidate.isIncome() && candidate.suggestedCategory.isEmpty()) {
            candidate.suggestedCategory = "Income";
        }
        candidate.status = Candidate.STATUS_NEW;
        return candidate;
    }

    private static Candidate baseCandidate(String packageName, String appName, String key, long postedAt,
                                           String title, String body) {
        Candidate candidate = new Candidate();
        candidate.notificationKey = key;
        candidate.packageName = packageName;
        candidate.appName = appName;
        candidate.title = ExpenseParser.safe(title);
        candidate.text = ExpenseParser.safe(body);
        candidate.postedAt = ExpenseParser.transactionTime(title, body, postedAt);
        candidate.originalAmount = "";
        candidate.originalCurrency = "";
        candidate.note = "";
        candidate.transactionType = Candidate.TYPE_EXPENSE;
        return candidate;
    }

    private String merchantFromSource(String title, String body, ExpenseParser.Amount amount) {
        if ("empty".equals(merchantSource)) {
            return "";
        }
        if ("title".equals(merchantSource)) {
            return ExpenseParser.cleanupMerchant(title);
        }
        return ExpenseParser.guessMerchant(title, body, amount);
    }

    private static String paymentMethod(String combined, String packageName, String appName, String template, String card) {
        if ("@packageDefault".equals(template)) {
            return ExpenseParser.paymentMethodFor(packageName, appName, combined);
        }
        if ("@walletCard".equals(template)) {
            String wallet = ExpenseParser.walletPaymentMethod(combined);
            return wallet.isEmpty() ? ExpenseParser.DEFAULT_PAYMENT_METHOD : wallet;
        }
        return ExpenseParser.safe(template).replace("${card}", ExpenseParser.safe(card));
    }

    private static String category(String merchant, String template) {
        if ("@keyword".equals(template)) {
            return ExpenseParser.categoryFor(merchant);
        }
        if ("@accountDebitKeyword".equals(template)) {
            return ExpenseParser.accountDebitCategory(merchant);
        }
        return ExpenseParser.safe(template);
    }

    private static String group(Matcher matcher, String original, String name) {
        try {
            int start = matcher.start(name);
            int end = matcher.end(name);
            if (start < 0 || end < 0) {
                return "";
            }
            return original.substring(start, end);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String valueOrGroup(Matcher matcher, String original, String value, String group) {
        if (value == null || value.isEmpty()) {
            return group(matcher, original, group).toUpperCase(Locale.ROOT);
        }
        if (value.startsWith("$")) {
            return group(matcher, original, value.substring(1)).toUpperCase(Locale.ROOT);
        }
        return value;
    }
}
