package dev.fanis.expensenotification;

import android.content.Context;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;

/**
 * Entry points for turning a notification into an expense {@link Candidate}, plus
 * the language-level heuristics (amounts, merchants, categories, Greek folding)
 * shared by every input source. The config-driven matching machinery lives in
 * {@link ConfiguredParser}/{@link InputSource}/{@link InputRule}.
 */
final class ExpenseParser {
    // Default payment method for card purchases whose specific card can't be
    // identified. Must match an existing payment-method name in Expense Manager,
    // otherwise the prefill intent leaves the field blank.
    static final String DEFAULT_PAYMENT_METHOD = "Credit Card";

    // A money amount: grouped thousands with decimals (1.234,56), grouped thousands
    // without decimals (1.234 = one thousand two hundred thirty four), or a plain
    // value (300,00 / 12.34 / 1234). normalizeAmount() resolves which separator is
    // the decimal point afterwards.
    static final String MONEY =
            "(?:[0-9]{1,3}(?:[.,][0-9]{3})+[.,][0-9]{2}(?![0-9])"
                    + "|[0-9]{1,3}(?:[.,][0-9]{3})+(?![0-9])"
                    + "|[0-9]+(?:[.,][0-9]{2})?)";

    // The transaction date the bank wrote into the SMS body (Cyprus/EU day-first:
    // dd/MM/yyyy or dd/MM/yy, optional HH:mm after a space or comma). Used to date the
    // expense to when the payment happened rather than when the notification arrived,
    // which can be much later for a delayed or re-posted SMS.
    private static final Pattern TRANSACTION_DATE = Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b(?:[\\s,]+(\\d{1,2}):(\\d{2}))?");

    private static final Pattern SYMBOL_AMOUNT = Pattern.compile("([\\u20AC$\\u00A3])\\s*(" + MONEY + ")");
    private static final Pattern AMOUNT_SYMBOL = Pattern.compile("(" + MONEY + ")\\s*([\\u20AC$\\u00A3])");
    private static final Pattern ISO_AMOUNT = Pattern.compile("\\b(EUR|USD|GBP)\\s*(" + MONEY + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_ISO = Pattern.compile("\\b(" + MONEY + ")\\s*(EUR|USD|GBP)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAID_AT = Pattern.compile(
            "\\bpaid\\s+(?:[\\u20AC$\\u00A3]\\s*)?(?:EUR|USD|GBP)?\\s*" + MONEY +
                    "(?:\\s*\\(\\s*[\\u20AC$\\u00A3]\\s*" + MONEY + "\\s*\\))?" +
                    "\\s+(?:[A-Z]{3}\\s+)?at\\s+([^\\n\\r]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_CURRENCY_PAID = Pattern.compile(
            "\\bpaid\\s+([\\u20AC$\\u00A3])\\s*(" + MONEY + ")" +
                    "\\s*\\(\\s*([\\u20AC$\\u00A3])\\s*(" + MONEY + ")\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_BALANCE_CCY = Pattern.compile(
            "\\b(EUR|USD|GBP)\\s+Balance\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WALLET_PAYMENT_METHOD = Pattern.compile(
            "(?m)^\\s*(?:(?:[\\u20AC$\\u00A3]\\s*)?" + MONEY + "|(?:EUR|USD|GBP)\\s*" + MONEY + "|" + MONEY + "\\s*(?:EUR|USD|GBP))\\s+with\\s+([^\\n\\r]+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private ExpenseParser() {
    }

    static Candidate parse(Context context, String packageName, String appName, String key, long postedAt, String title, String body) {
        return ConfiguredParser.forContext(context).parse(packageName, appName, key, postedAt, title, body);
    }

    static boolean isWatched(Context context, String packageName, String title) {
        return ConfiguredParser.forContext(context).isWatched(packageName, title);
    }

    static ParseDiagnostics debug(Context context, String packageName, String appName, String key, long postedAt, String title, String body) {
        return ConfiguredParser.forContext(context).debug(packageName, appName, key, postedAt, title, body);
    }

    /** Parses against a single input config with the default global config (embedded config tests). */
    static Candidate parseInputConfig(String fileName, String inputJson, String packageName, String appName, String key,
                                      long postedAt, String title, String body) throws JSONException {
        LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        inputs.put(fileName == null || fileName.isEmpty() ? "input.json" : fileName, inputJson);
        return ConfiguredParser.fromJson(GlobalConfig.defaultJson(), inputs).parse(packageName, appName, key, postedAt, title, body);
    }

    /** Parses against an explicit global + input set; lets JVM tests run the real asset configs. */
    static Candidate parseConfigured(String globalJson, LinkedHashMap<String, String> inputJsonByFile,
                                     String packageName, String appName, String key, long postedAt,
                                     String title, String body) throws JSONException {
        return ConfiguredParser.fromJson(globalJson, inputJsonByFile).parse(packageName, appName, key, postedAt, title, body);
    }

    static Set<String> defaultSmsPackages() {
        return GlobalConfig.defaults().smsPackages;
    }

    static final class ParseDiagnostics {
        final Candidate candidate;
        final String matchedSource;
        final String matchedRule;
        final List<String> steps;

        ParseDiagnostics(Candidate candidate, String matchedSource, String matchedRule, List<String> steps) {
            this.candidate = candidate;
            this.matchedSource = matchedSource;
            this.matchedRule = matchedRule;
            this.steps = steps;
        }

        boolean matched() {
            return candidate != null;
        }
    }

    static boolean isZero(String value) {
        try {
            return new BigDecimal(value).signum() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isInteresting(Candidate candidate) {
        if (candidate.hasAmount()) {
            return true;
        }
        String haystack = (candidate.title + " " + candidate.text).toLowerCase(Locale.ROOT);
        return haystack.contains("payment") || haystack.contains("purchase") || haystack.contains("card");
    }

    static Amount extractAmount(String text) {
        Matcher matcher = SYMBOL_AMOUNT.matcher(text);
        if (matcher.find()) {
            return new Amount(currencyFromSymbol(matcher.group(1)), normalizeAmount(matcher.group(2)));
        }
        matcher = AMOUNT_SYMBOL.matcher(text);
        if (matcher.find()) {
            return new Amount(currencyFromSymbol(matcher.group(2)), normalizeAmount(matcher.group(1)));
        }
        matcher = ISO_AMOUNT.matcher(text);
        if (matcher.find()) {
            return new Amount(matcher.group(1).toUpperCase(Locale.ROOT), normalizeAmount(matcher.group(2)));
        }
        matcher = AMOUNT_ISO.matcher(text);
        if (matcher.find()) {
            return new Amount(matcher.group(2).toUpperCase(Locale.ROOT), normalizeAmount(matcher.group(1)));
        }
        return null;
    }

    static MultiCurrencyAmount extractMultiCurrencyAmount(String text) {
        Matcher matcher = MULTI_CURRENCY_PAID.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        Amount charged = new Amount(currencyFromSymbol(matcher.group(1)), normalizeAmount(matcher.group(2)));
        Amount converted = new Amount(currencyFromSymbol(matcher.group(3)), normalizeAmount(matcher.group(4)));
        if (charged.currency.equalsIgnoreCase(converted.currency)) {
            return null;
        }

        String accountCcy = accountCurrencyFromBody(text);
        // Revolut's parenthesized amount is the conversion into the account currency,
        // so by default that side is "account" and the leading amount is "original".
        // If the body's "<CCY> Balance:" line says otherwise, swap.
        if (accountCcy != null && accountCcy.equalsIgnoreCase(charged.currency)
                && !accountCcy.equalsIgnoreCase(converted.currency)) {
            return new MultiCurrencyAmount(charged, converted);
        }
        return new MultiCurrencyAmount(converted, charged);
    }

    private static String accountCurrencyFromBody(String text) {
        Matcher matcher = ACCOUNT_BALANCE_CCY.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    static String accountDebitCategory(String description) {
        String folded = foldAmbiguousGreek(description).toUpperCase(Locale.ROOT);
        if (folded.contains("PROM") || folded.contains("ΠPOM")
                || folded.contains(foldAmbiguousGreek("ΠΡΟΜΗΘΕΙΑ").toUpperCase(Locale.ROOT))) {
            return "Bank fees";
        }
        if (folded.contains("META") || folded.contains("ΦOPA")
                || folded.contains(foldAmbiguousGreek("ΜΕΤΑΦΟΡΑ").toUpperCase(Locale.ROOT))) {
            return "Transfers";
        }
        return categoryFor(description);
    }

    static String guessMerchant(String title, String body, Amount amount) {
        String combined = safe(title) + "\n" + safe(body);
        Matcher paidAt = PAID_AT.matcher(combined);
        if (paidAt.find()) {
            return cleanupMerchant(paidAt.group(1));
        }

        String[] lines = combined.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (amount != null && line.contains(amount.value)) {
                continue;
            }
            if (lower.contains("revolut") || lower.contains("google wallet") || lower.contains("payment") ||
                    lower.contains("purchase") || lower.contains("approved") || lower.contains("declined")) {
                continue;
            }
            if (lower.equals("view your purchase") || WALLET_PAYMENT_METHOD.matcher(line).matches() ||
                    isMaskedWalletDetail(line)) {
                continue;
            }
            return line;
        }
        String fallback = safe(title).trim();
        return isNonMerchantLine(fallback) ? "Unknown" : fallback;
    }

    static String cleanupMerchant(String merchant) {
        String cleaned = safe(merchant).trim().replaceAll("\\s+", " ");
        int balanceIndex = cleaned.toLowerCase(Locale.ROOT).indexOf(" eur balance");
        if (balanceIndex >= 0) {
            cleaned = cleaned.substring(0, balanceIndex).trim();
        }
        return cleaned.isEmpty() ? "Unknown" : cleaned;
    }

    private static boolean isNonMerchantLine(String line) {
        String lower = safe(line).trim().toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.contains("revolut") || lower.contains("google wallet") ||
                lower.contains("payment") || lower.contains("purchase") ||
                lower.contains("approved") || lower.contains("declined") ||
                lower.equals("view your purchase") ||
                WALLET_PAYMENT_METHOD.matcher(line).matches() ||
                isMaskedWalletDetail(line);
    }

    static String categoryFor(String merchant) {
        String lower = safe(merchant).toLowerCase(Locale.ROOT);
        if (lower.contains("alphamega") || lower.contains("supermarket") || lower.contains("lidl") ||
                lower.contains("metro") || lower.contains("sklavenitis")) {
            return "Groceries";
        }
        if (lower.contains("zorbas") || lower.contains("cafe") || lower.contains("restaurant") ||
                lower.contains("coffee")) {
            return "Dining";
        }
        if (lower.contains("petrol") || lower.contains("shell") || lower.contains("esso") ||
                lower.contains("eko")) {
            return "Fuel";
        }
        return "";
    }

    static String paymentMethodFor(String packageName, String appName, String notificationText) {
        String lowerPackage = safe(packageName).toLowerCase(Locale.ROOT);
        String lowerApp = safe(appName).toLowerCase(Locale.ROOT);
        if (lowerPackage.contains("revolut") || lowerApp.contains("revolut")) {
            // Revolut card payments are billed to the credit card too.
            return DEFAULT_PAYMENT_METHOD;
        }
        if (lowerPackage.contains("walletnfcrel") || lowerApp.contains("wallet")) {
            // Google Wallet pays via the underlying card (e.g. "Travel Card"); fall
            // back to the default card only when the card name can't be read.
            String walletMethod = walletPaymentMethod(notificationText);
            return walletMethod.isEmpty() ? DEFAULT_PAYMENT_METHOD : walletMethod;
        }
        // Unknown source: the app name is never a real Expense Manager payment method,
        // so prefilling it leaves the field blank. Default to the credit card instead.
        return DEFAULT_PAYMENT_METHOD;
    }

    static String walletPaymentMethod(String notificationText) {
        Matcher matcher = WALLET_PAYMENT_METHOD.matcher(safe(notificationText));
        if (!matcher.find()) {
            return "";
        }
        String method = matcher.group(1).trim().replaceAll("\\s+", " ");
        if (method.equalsIgnoreCase("Google Wallet") || isGenericCardName(method)) {
            return "";
        }
        return method;
    }

    // A raw card descriptor like "Revolut Visa **0000" is not a payment method the
    // user keeps; only a friendly card name (e.g. "Travel Card") is. Generic
    // descriptors fall back to the default card.
    private static boolean isGenericCardName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasBrand = lower.contains("visa") || lower.contains("mastercard")
                || lower.contains("maestro") || lower.contains("amex")
                || lower.contains("american express");
        boolean hasMask = name.indexOf('*') >= 0 || name.indexOf('•') >= 0;
        return hasBrand || hasMask;
    }

    private static boolean isMaskedWalletDetail(String line) {
        String cleaned = safe(line).trim();
        return cleaned.matches(".*\\d.*") && cleaned.matches("[0-9 .\\-*\\u2022]+");
    }

    private static String currencyFromSymbol(String symbol) {
        if ("€".equals(symbol)) {
            return "EUR";
        }
        if ("$".equals(symbol)) {
            return "USD";
        }
        if ("£".equals(symbol)) {
            return "GBP";
        }
        return symbol;
    }

    // Date the expense to the transaction date in the notification text when present,
    // falling back to when the notification was posted. Time of day is kept when the
    // message includes it, otherwise noon (a safe hour that never rolls the calendar
    // day over across time zones).
    static long transactionTime(String title, String body, long fallback) {
        Matcher matcher = TRANSACTION_DATE.matcher(safe(title) + "\n" + safe(body));
        if (!matcher.find()) {
            return fallback;
        }
        try {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int year = Integer.parseInt(matcher.group(3));
            if (year < 100) {
                year += 2000;
            }
            if (day < 1 || day > 31 || month < 1 || month > 12) {
                return fallback;
            }
            int hour = 12;
            int minute = 0;
            if (matcher.group(4) != null) {
                int h = Integer.parseInt(matcher.group(4));
                int m = Integer.parseInt(matcher.group(5));
                if (h <= 23 && m <= 59) {
                    hour = h;
                    minute = m;
                }
            }
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.setLenient(false);
            calendar.set(year, month - 1, day, hour, minute, 0);
            return calendar.getTimeInMillis();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static String normalizeAmount(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            // Both separators present: the rightmost is the decimal point, the other groups thousands.
            if (lastComma > lastDot) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // Single comma: exactly 3 digits after it is a thousands group (1,234);
            // otherwise it's an el-CY / el-GR decimal separator (300,00).
            if (s.length() - lastComma - 1 == 3) {
                s = s.replace(",", "");
            } else {
                s = s.replace(',', '.');
            }
        } else if (lastDot >= 0 && s.length() - lastDot - 1 == 3) {
            // Single dot with exactly 3 digits after it: European thousands (1.234).
            s = s.replace(".", "");
        }
        return s;
    }

    // Replace each Greek capital that has an identical-looking Latin twin with that
    // twin. 1:1 and length-preserving, so match offsets stay valid against the
    // original string. Greek letters with no Latin lookalike are left as-is and
    // serve as unambiguous anchors in the patterns.
    static String foldAmbiguousGreek(String text) {
        if (text == null) {
            return "";
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = foldGreekChar(chars[i]);
        }
        return new String(chars);
    }

    private static char foldGreekChar(char c) {
        switch (c) {
            case 'Α': return 'A'; // Alpha
            case 'Β': return 'B'; // Beta
            case 'Ε': return 'E'; // Epsilon
            case 'Ζ': return 'Z'; // Zeta
            case 'Η': return 'H'; // Eta
            case 'Ι': return 'I'; // Iota
            case 'Κ': return 'K'; // Kappa
            case 'Μ': return 'M'; // Mu
            case 'Ν': return 'N'; // Nu
            case 'Ο': return 'O'; // Omicron
            case 'Ρ': return 'P'; // Rho
            case 'Τ': return 'T'; // Tau
            case 'Υ': return 'Y'; // Upsilon
            case 'Χ': return 'X'; // Chi
            default: return c;
        }
    }

    static String safe(String text) {
        return text == null ? "" : text;
    }

    static final class Amount {
        final String currency;
        final String value;

        Amount(String currency, String value) {
            this.currency = currency;
            this.value = value;
        }
    }

    static final class MultiCurrencyAmount {
        final Amount account;
        final Amount original;

        MultiCurrencyAmount(Amount account, Amount original) {
            this.account = account;
            this.original = original;
        }
    }
}
