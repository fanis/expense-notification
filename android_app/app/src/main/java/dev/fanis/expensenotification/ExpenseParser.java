package dev.fanis.expensenotification;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ExpenseParser {
    // Default payment method for card purchases whose specific card can't be
    // identified. Must match an existing payment-method name in Expense Manager,
    // otherwise the prefill intent leaves the field blank.
    private static final String DEFAULT_PAYMENT_METHOD = "Credit Card";

    private static final Pattern SYMBOL_AMOUNT = Pattern.compile("([\\u20AC$\\u00A3])\\s*([0-9]+(?:[.,][0-9]{2})?)");
    private static final Pattern AMOUNT_SYMBOL = Pattern.compile("([0-9]+(?:[.,][0-9]{2})?)\\s*([\\u20AC$\\u00A3])");
    private static final Pattern ISO_AMOUNT = Pattern.compile("\\b(EUR|USD|GBP)\\s*([0-9]+(?:[.,][0-9]{2})?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_ISO = Pattern.compile("\\b([0-9]+(?:[.,][0-9]{2})?)\\s*(EUR|USD|GBP)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAID_AT = Pattern.compile(
            "\\bpaid\\s+(?:[\\u20AC$\\u00A3]\\s*)?(?:EUR|USD|GBP)?\\s*[0-9]+(?:[.,][0-9]{2})?" +
                    "(?:\\s*\\(\\s*[\\u20AC$\\u00A3]\\s*[0-9]+(?:[.,][0-9]{2})?\\s*\\))?" +
                    "\\s+(?:[A-Z]{3}\\s+)?at\\s+([^\\n\\r]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_CURRENCY_PAID = Pattern.compile(
            "\\bpaid\\s+([\\u20AC$\\u00A3])\\s*([0-9]+(?:[.,][0-9]{2})?)" +
                    "\\s*\\(\\s*([\\u20AC$\\u00A3])\\s*([0-9]+(?:[.,][0-9]{2})?)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_BALANCE_CCY = Pattern.compile(
            "\\b(EUR|USD|GBP)\\s+Balance\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WALLET_PAYMENT_METHOD = Pattern.compile(
            "(?m)^\\s*(?:(?:[\\u20AC$\\u00A3]\\s*)?[0-9]+(?:[.,][0-9]{2})?|(?:EUR|USD|GBP)\\s*[0-9]+(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})?\\s*(?:EUR|USD|GBP))\\s+with\\s+([^\\n\\r]+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // A money amount with optional grouped thousands (1.234,56) or a plain value (300,00 / 12.34).
    // normalizeAmount() resolves which separator is the decimal point afterwards.
    private static final String MONEY = "(?:[0-9]{1,3}(?:[.,][0-9]{3})*[.,][0-9]{2}|[0-9]+(?:[.,][0-9]{2})?)";

    private ExpenseParser() {
    }

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

    static Candidate parse(String packageName, String appName, String key, long postedAt, String title, String body) {
        return ConfiguredParser.defaults().parse(packageName, appName, key, postedAt, title, body);
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

    static Candidate parseInputConfig(String fileName, String inputJson, String packageName, String appName, String key,
                                      long postedAt, String title, String body) throws JSONException {
        LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        inputs.put(fileName == null || fileName.isEmpty() ? "input.json" : fileName, inputJson);
        return ConfiguredParser.fromJson(DefaultJson.global(), inputs).parse(packageName, appName, key, postedAt, title, body);
    }

    static String defaultInputJson(String fileName) {
        String json = DefaultJson.inputsByFile().get(fileName);
        return json == null ? "" : json;
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

    private static boolean isRejectedNotification(String combined) {
        String lower = safe(combined).toLowerCase(Locale.ROOT);
        for (String phrase : REJECT_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZero(String value) {
        try {
            return new BigDecimal(value).signum() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isInteresting(Candidate candidate) {
        if (candidate.hasAmount()) {
            return true;
        }
        String haystack = (candidate.title + " " + candidate.text).toLowerCase(Locale.ROOT);
        return haystack.contains("payment") || haystack.contains("purchase") || haystack.contains("card");
    }

    private static Amount extractAmount(String text) {
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

    private static MultiCurrencyAmount extractMultiCurrencyAmount(String text) {
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

    private static String accountDebitCategory(String description) {
        String folded = foldAmbiguousGreek(description).toUpperCase(Locale.ROOT);
        if (folded.contains("PROM") || folded.contains("\u03A0POM")
                || folded.contains(foldAmbiguousGreek("\u03A0\u03A1\u039F\u039C\u0397\u0398\u0395\u0399\u0391").toUpperCase(Locale.ROOT))) {
            return "Bank fees";
        }
        if (folded.contains("META") || folded.contains("\u03A6OPA")
                || folded.contains(foldAmbiguousGreek("\u039C\u0395\u03A4\u0391\u03A6\u039F\u03A1\u0391").toUpperCase(Locale.ROOT))) {
            return "Transfers";
        }
        return categoryFor(description);
    }

    private static String guessMerchant(String title, String body, Amount amount) {
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

    private static String cleanupMerchant(String merchant) {
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

    private static String categoryFor(String merchant) {
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

    private static String paymentMethodFor(String packageName, String appName, String notificationText) {
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

    private static String walletPaymentMethod(String notificationText) {
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
        boolean hasMask = name.indexOf('*') >= 0 || name.indexOf('\u2022') >= 0;
        return hasBrand || hasMask;
    }

    private static boolean isMaskedWalletDetail(String line) {
        String cleaned = safe(line).trim();
        return cleaned.matches(".*\\d.*") && cleaned.matches("[0-9 .\\-*\\u2022]+");
    }

    private static String currencyFromSymbol(String symbol) {
        if ("\u20AC".equals(symbol)) {
            return "EUR";
        }
        if ("$".equals(symbol)) {
            return "USD";
        }
        if ("\u00A3".equals(symbol)) {
            return "GBP";
        }
        return symbol;
    }

    private static String normalizeAmount(String raw) {
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
            // Single comma: decimal separator (el-CY / el-GR style).
            s = s.replace(',', '.');
        }
        return s;
    }

    // Replace each Greek capital that has an identical-looking Latin twin with that
    // twin. 1:1 and length-preserving, so match offsets stay valid against the
    // original string. Greek letters with no Latin lookalike are left as-is and
    // serve as unambiguous anchors in the patterns.
    private static String foldAmbiguousGreek(String text) {
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
            case '\u0391': return 'A'; // Alpha
            case '\u0392': return 'B'; // Beta
            case '\u0395': return 'E'; // Epsilon
            case '\u0396': return 'Z'; // Zeta
            case '\u0397': return 'H'; // Eta
            case '\u0399': return 'I'; // Iota
            case '\u039A': return 'K'; // Kappa
            case '\u039C': return 'M'; // Mu
            case '\u039D': return 'N'; // Nu
            case '\u039F': return 'O'; // Omicron
            case '\u03A1': return 'P'; // Rho
            case '\u03A4': return 'T'; // Tau
            case '\u03A5': return 'Y'; // Upsilon
            case '\u03A7': return 'X'; // Chi
            default: return c;
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static final class ConfiguredParser {
        private static final ConfiguredParser DEFAULTS = fromDefaults();
        private static ConfiguredParser cachedContextParser;
        private static long cachedRevision = Long.MIN_VALUE;
        private static String cachedFilesDir = "";

        private final GlobalConfig global;
        private final List<InputSource> sources;

        private ConfiguredParser(GlobalConfig global, List<InputSource> sources) {
            this.global = global;
            this.sources = sources;
        }

        static ConfiguredParser defaults() {
            return DEFAULTS;
        }

        static ConfiguredParser forContext(Context context) {
            if (context == null) {
                return defaults();
            }
            Context appContext = context.getApplicationContext();
            long revision = ConfigRevision.current(appContext);
            String filesDir = appContext.getFilesDir() == null ? "" : appContext.getFilesDir().getAbsolutePath();
            synchronized (ConfiguredParser.class) {
                if (cachedContextParser != null && cachedRevision == revision && cachedFilesDir.equals(filesDir)) {
                    return cachedContextParser;
                }
            }
            try {
                ConfiguredParser parser = fromContext(appContext);
                synchronized (ConfiguredParser.class) {
                    cachedContextParser = parser;
                    cachedRevision = revision;
                    cachedFilesDir = filesDir;
                }
                return parser;
            } catch (Exception ignored) {
                return defaults();
            }
        }

        boolean isWatched(String packageName, String title) {
            for (InputSource source : sources) {
                if (source.enabled && source.matches(packageName, title, global.smsPackages)) {
                    return true;
                }
            }
            return false;
        }

        Candidate parse(String packageName, String appName, String key, long postedAt, String title, String body) {
            String combined = (safe(title) + "\n" + safe(body)).trim();
            if (isRejected(combined)) {
                return null;
            }
            for (InputSource source : sources) {
                if (!source.enabled || !source.matches(packageName, title, global.smsPackages)) {
                    continue;
                }
                for (InputRule rule : source.rules) {
                    Candidate candidate = rule.parse(source, packageName, appName, key, postedAt, title, body, combined);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        ParseDiagnostics debug(String packageName, String appName, String key, long postedAt, String title, String body) {
            ArrayList<String> steps = new ArrayList<>();
            String combined = (safe(title) + "\n" + safe(body)).trim();
            if (isRejected(combined)) {
                steps.add("Rejected by global reject phrase.");
                return new ParseDiagnostics(null, "", "", steps);
            }
            for (InputSource source : sources) {
                String sourceName = source.displayName + " (" + source.id + ")";
                if (!source.enabled) {
                    steps.add("Skipped disabled source: " + sourceName);
                    continue;
                }
                if (!source.matches(packageName, title, global.smsPackages)) {
                    steps.add("Source did not match app/sender: " + sourceName);
                    continue;
                }
                steps.add("Source matched app/sender: " + sourceName);
                for (InputRule rule : source.rules) {
                    Candidate candidate = rule.parse(source, packageName, appName, key, postedAt, title, body, combined);
                    if (candidate != null) {
                        steps.add("Rule matched: " + rule.name + " (" + rule.type + ")");
                        return new ParseDiagnostics(candidate, source.displayName, rule.name, steps);
                    }
                    steps.add("Rule did not match: " + rule.name + " (" + rule.type + ")");
                }
            }
            return new ParseDiagnostics(null, "", "", steps);
        }

        private boolean isRejected(String combined) {
            String lower = safe(combined).toLowerCase(Locale.ROOT);
            for (String phrase : global.rejectPhrases) {
                if (lower.contains(phrase)) {
                    return true;
                }
            }
            return false;
        }

        private static ConfiguredParser fromDefaults() {
            try {
                return fromJson(DefaultJson.global(), DefaultJson.inputsByFile());
            } catch (Exception ignored) {
                return new ConfiguredParser(GlobalConfig.defaults(), new ArrayList<>());
            }
        }

        private static ConfiguredParser fromContext(Context context) throws IOException, JSONException {
            LinkedHashMap<String, String> inputs = new LinkedHashMap<>(DefaultJson.inputsByFile());
            String globalJson = DefaultJson.global();
            AssetManager assets = context.getAssets();
            try {
                String assetGlobal = readAsset(assets, "global.json");
                if (!assetGlobal.isEmpty()) {
                    globalJson = assetGlobal;
                }
            } catch (IOException ignored) {
            }
            try {
                String[] names = assets.list("inputs");
                if (names != null) {
                    ArrayList<String> sorted = new ArrayList<>();
                    Collections.addAll(sorted, names);
                    Collections.sort(sorted);
                    for (String name : sorted) {
                        if (name.endsWith(".json") && !ConfigHides.isHidden(context, "inputs", name)) {
                            inputs.put(name, readAsset(assets, "inputs/" + name));
                        }
                    }
                }
            } catch (IOException ignored) {
            }

            File userInputs = new File(context.getFilesDir(), "inputs");
            File[] files = userInputs.listFiles();
            if (files != null) {
                ArrayList<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, files);
                Collections.sort(sorted, Comparator.comparing(File::getName));
                for (File file : sorted) {
                    if (file.isFile() && file.getName().endsWith(".json")) {
                        inputs.put(file.getName(), readFile(file));
                    }
                }
            }
            return fromJson(globalJson, inputs, SmsApps.confirmedPackages(context));
        }

        private static ConfiguredParser fromJson(String globalJson, LinkedHashMap<String, String> inputJsonByFile) throws JSONException {
            return fromJson(globalJson, inputJsonByFile, Collections.emptySet());
        }

        private static ConfiguredParser fromJson(String globalJson, LinkedHashMap<String, String> inputJsonByFile,
                                                 Set<String> extraSmsPackages) throws JSONException {
            GlobalConfig global = GlobalConfig.fromJson(new JSONObject(globalJson)).withSmsPackages(extraSmsPackages);
            ArrayList<InputSource> sources = new ArrayList<>();
            for (Map.Entry<String, String> entry : inputJsonByFile.entrySet()) {
                sources.add(InputSource.fromJson(entry.getKey(), new JSONObject(entry.getValue())));
            }
            Collections.sort(sources, Comparator.comparingInt(source -> source.priority));
            return new ConfiguredParser(global, sources);
        }

        private static String readAsset(AssetManager assets, String name) throws IOException {
            try (InputStream in = assets.open(name)) {
                return readAll(in);
            }
        }

        private static String readFile(File file) throws IOException {
            try (InputStream in = new FileInputStream(file)) {
                return readAll(in);
            }
        }

        private static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class GlobalConfig {
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
                    set("com.textra", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
                    list(REJECT_PHRASES),
                    true);
        }

        static GlobalConfig fromJson(JSONObject json) {
            return new GlobalConfig(
                    setFromJson(json.optJSONArray("smsPackages"), defaults().smsPackages),
                    strings(json.optJSONArray("rejectPhrases"), defaults().rejectPhrases),
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

    private static final class InputSource {
        final String id;
        final String displayName;
        final boolean enabled;
        final int priority;
        final Set<String> packages;
        final Set<String> senders;
        final List<String> transforms;
        final List<InputRule> rules;

        private InputSource(String id, String displayName, boolean enabled, int priority, Set<String> packages,
                            Set<String> senders, List<String> transforms, List<InputRule> rules) {
            this.id = id;
            this.displayName = displayName;
            this.enabled = enabled;
            this.priority = priority;
            this.packages = packages;
            this.senders = senders;
            this.transforms = transforms;
            this.rules = rules;
        }

        static InputSource fromJson(String fileName, JSONObject json) {
            JSONObject match = json.optJSONObject("match");
            List<InputRule> rules = new ArrayList<>();
            JSONArray rawRules = json.optJSONArray("rules");
            if (rawRules != null) {
                for (int i = 0; i < rawRules.length(); i++) {
                    JSONObject rawRule = rawRules.optJSONObject(i);
                    if (rawRule != null) {
                        rules.add(InputRule.fromJson(rawRule));
                    }
                }
            }
            return new InputSource(
                    json.optString("id", stripJson(fileName)),
                    json.optString("displayName", stripJson(fileName)),
                    json.optBoolean("enabled", true),
                    json.optInt("priority", 100),
                    setFromJson(match == null ? null : match.optJSONArray("packages"), Collections.emptySet()),
                    lowerSet(strings(match == null ? null : match.optJSONArray("senders"), Collections.emptyList())),
                    strings(json.optJSONArray("transforms"), Collections.emptyList()),
                    rules);
        }

        boolean matches(String packageName, String title, Set<String> smsPackages) {
            String safePackage = safe(packageName);
            if (packages.contains(safePackage)) {
                return true;
            }
            if (!senders.isEmpty() && smsPackages.contains(safePackage)) {
                String lowerTitle = safe(title).trim().toLowerCase(Locale.ROOT);
                return senders.contains(lowerTitle);
            }
            return packages.isEmpty() && senders.isEmpty();
        }
    }

    private static final class InputRule {
        final String name;
        final String type;
        final String pattern;
        final int flags;
        final List<String> transforms;
        final JSONObject output;
        final String merchantSource;
        final String noteSource;

        private InputRule(String name, String type, String pattern, int flags, List<String> transforms,
                          JSONObject output, String merchantSource, String noteSource) {
            this.name = name;
            this.type = type;
            this.pattern = pattern;
            this.flags = flags;
            this.transforms = transforms;
            this.output = output == null ? new JSONObject() : output;
            this.merchantSource = merchantSource;
            this.noteSource = noteSource;
        }

        static InputRule fromJson(JSONObject json) {
            return new InputRule(
                    json.optString("name", ""),
                    json.optString("type", "regex"),
                    json.optString("pattern", ""),
                    flags(json.optJSONArray("flags")),
                    strings(json.optJSONArray("transforms"), Collections.emptyList()),
                    json.optJSONObject("output"),
                    json.optString("merchantSource", ""),
                    json.optString("noteSource", ""));
        }

        Candidate parse(InputSource source, String packageName, String appName, String key, long postedAt,
                        String title, String body, String combined) {
            if ("amountFallback".equals(type)) {
                return amountFallback(source, packageName, appName, key, postedAt, title, body, combined);
            }
            if (!"regex".equals(type) || pattern.isEmpty()) {
                return null;
            }
            return regexCandidate(source, packageName, appName, key, postedAt, title, body, combined);
        }

        private Candidate amountFallback(InputSource source, String packageName, String appName, String key, long postedAt,
                                         String title, String body, String combined) {
            boolean multiCurrency = hasTransform(source, "multiCurrency");
            MultiCurrencyAmount multi = multiCurrency ? extractMultiCurrencyAmount(combined) : null;
            Amount amount = multi != null ? multi.account : extractAmount(combined);
            if (amount == null) {
                return null;
            }
            if (isZero(amount.value)) {
                return null;
            }
            Candidate candidate = baseCandidate(packageName, appName, key, postedAt, title, body);
            candidate.amount = amount.value;
            candidate.currency = amount.currency;
            if (multi != null) {
                candidate.originalAmount = multi.original.value;
                candidate.originalCurrency = multi.original.currency;
            }
            candidate.merchant = merchantFromSource(merchantSource, title, body, amount);
            candidate.note = "body".equals(noteSource) ? safe(body) : "";
            candidate.suggestedPaymentMethod = paymentMethod(combined, packageName, appName, output.optString("paymentMethod", "@packageDefault"), "");
            candidate.suggestedCategory = category(candidate.merchant, output.optString("category", "@keyword"));
            candidate.status = "NEW";
            return isInteresting(candidate) ? candidate : null;
        }

        private Candidate regexCandidate(InputSource source, String packageName, String appName, String key, long postedAt,
                                         String title, String body, String combined) {
            boolean foldGreek = hasTransform(source, "foldGreek") || transforms.contains("foldGreek");
            String target = foldGreek ? foldAmbiguousGreek(combined) : combined;
            String rawPattern = foldGreek ? foldAmbiguousGreek(pattern) : pattern;
            Matcher matcher = Pattern.compile(rawPattern, flags).matcher(target);
            if (!matcher.find()) {
                return null;
            }
            String amount = normalizeAmount(group(matcher, combined, "amount"));
            if (amount.isEmpty()) {
                return null;
            }
            if (isZero(amount)) {
                return null;
            }
            Candidate candidate = baseCandidate(packageName, appName, key, postedAt, title, body);
            candidate.amount = amount;
            candidate.currency = valueOrGroup(matcher, combined, output.optString("currency", ""), "currency");
            candidate.originalAmount = "";
            candidate.originalCurrency = "";
            String merchant = group(matcher, combined, "merchant");
            if (merchant.isEmpty()) {
                merchant = merchantFromSource(merchantSource, title, body, new Amount(candidate.currency, candidate.amount));
            }
            candidate.merchant = cleanupMerchant(merchant);
            candidate.note = group(matcher, combined, "note");
            if (candidate.note.isEmpty() && "body".equals(noteSource)) {
                candidate.note = safe(body);
            }
            String card = group(matcher, combined, "card");
            candidate.suggestedPaymentMethod = paymentMethod(combined, packageName, appName,
                    output.optString("paymentMethod", "@packageDefault"), card);
            String category = output.optString("category", "@keyword");
            candidate.suggestedCategory = category(candidate.merchant, category);
            candidate.transactionType = output.optString("type", "EXPENSE");
            if ("INCOME".equals(candidate.transactionType) && candidate.suggestedCategory.isEmpty()) {
                candidate.suggestedCategory = "Income";
            }
            candidate.status = "NEW";
            return candidate;
        }

        private boolean hasTransform(InputSource source, String name) {
            return source.transforms.contains(name) || transforms.contains(name);
        }
    }

    private static Candidate baseCandidate(String packageName, String appName, String key, long postedAt, String title, String body) {
        Candidate candidate = new Candidate();
        candidate.notificationKey = key;
        candidate.packageName = packageName;
        candidate.appName = appName;
        candidate.title = safe(title);
        candidate.text = safe(body);
        candidate.postedAt = postedAt;
        candidate.originalAmount = "";
        candidate.originalCurrency = "";
        candidate.note = "";
        candidate.transactionType = "EXPENSE";
        return candidate;
    }

    private static String merchantFromSource(String merchantSource, String title, String body, Amount amount) {
        if ("empty".equals(merchantSource)) {
            return "";
        }
        if ("title".equals(merchantSource)) {
            return cleanupMerchant(title);
        }
        return guessMerchant(title, body, amount);
    }

    private static String paymentMethod(String combined, String packageName, String appName, String template, String card) {
        if ("@packageDefault".equals(template)) {
            return paymentMethodFor(packageName, appName, combined);
        }
        if ("@walletCard".equals(template)) {
            String wallet = walletPaymentMethod(combined);
            return wallet.isEmpty() ? DEFAULT_PAYMENT_METHOD : wallet;
        }
        return safe(template).replace("${card}", safe(card));
    }

    private static String category(String merchant, String template) {
        if ("@keyword".equals(template)) {
            return categoryFor(merchant);
        }
        if ("@accountDebitKeyword".equals(template)) {
            return accountDebitCategory(merchant);
        }
        return safe(template);
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
            return safe(group(matcher, original, group)).toUpperCase(Locale.ROOT);
        }
        if (value.startsWith("$")) {
            return safe(group(matcher, original, value.substring(1))).toUpperCase(Locale.ROOT);
        }
        return value;
    }

    private static int flags(JSONArray json) {
        int flags = 0;
        if (json == null) {
            return flags;
        }
        for (int i = 0; i < json.length(); i++) {
            String flag = json.optString(i);
            if ("caseInsensitive".equals(flag)) {
                flags |= Pattern.CASE_INSENSITIVE;
            } else if ("unicodeCase".equals(flag)) {
                flags |= Pattern.UNICODE_CASE;
            } else if ("multiline".equals(flag)) {
                flags |= Pattern.MULTILINE;
            } else if ("dotall".equals(flag)) {
                flags |= Pattern.DOTALL;
            }
        }
        return flags;
    }

    private static List<String> strings(JSONArray json, List<String> fallback) {
        if (json == null) {
            return new ArrayList<>(fallback);
        }
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            result.add(json.optString(i));
        }
        return result;
    }

    private static Set<String> setFromJson(JSONArray json, Set<String> fallback) {
        if (json == null) {
            return new HashSet<>(fallback);
        }
        return new HashSet<>(strings(json, Collections.emptyList()));
    }

    private static Set<String> lowerSet(List<String> values) {
        HashSet<String> set = new HashSet<>();
        for (String value : values) {
            set.add(safe(value).trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static String stripJson(String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static List<String> list(String... values) {
        ArrayList<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
    }

    private static Set<String> set(String... values) {
        HashSet<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static final class DefaultJson {
        static String global() {
            return "{"
                    + "\"smsPackages\":[\"com.textra\",\"com.google.android.apps.messaging\",\"com.samsung.android.messaging\",\"com.android.mms\"],"
                    + "\"dropZeroAmount\":true,"
                    + "\"rejectPhrases\":["
                    + "\"waiting for your approval\",\"verify a payment\",\"verify your payment\",\"tap to start\","
                    + "\"declined\",\"insufficient balance\",\"insufficient funds\",\"verified your card\","
                    + "\"haven't been charged\",\"have not been charged\",\"card registration\",\"card verification\"]"
                    + "}";
        }

        static LinkedHashMap<String, String> inputsByFile() {
            LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
            inputs.put("revolut.json", "{"
                    + "\"id\":\"revolut\",\"displayName\":\"Revolut\",\"enabled\":true,\"priority\":10,"
                    + "\"match\":{\"packages\":[\"com.revolut.revolut\",\"com.revolut.business\"]},"
                    + "\"transforms\":[\"normalizeAmount\",\"multiCurrency\"],"
                    + "\"rules\":[{\"name\":\"default\",\"type\":\"amountFallback\",\"merchantSource\":\"firstNonNoiseLine\","
                    + "\"output\":{\"paymentMethod\":\"Credit Card\",\"category\":\"@keyword\"}}]}");
            inputs.put("google-wallet.json", "{"
                    + "\"id\":\"google-wallet\",\"displayName\":\"Google Wallet\",\"enabled\":true,\"priority\":20,"
                    + "\"match\":{\"packages\":[\"com.google.android.apps.walletnfcrel\"]},"
                    + "\"rules\":[{\"name\":\"wallet-purchase\",\"type\":\"amountFallback\",\"merchantSource\":\"title\","
                    + "\"output\":{\"paymentMethod\":\"@walletCard\",\"category\":\"@keyword\"}}]}");
            inputs.put("bank-of-cyprus.json", bankOfCyprusJson());
            inputs.put("eurobank.json", eurobankJson());
            inputs.put("alpha-bank.json", alphaBankJson());
            return inputs;
        }

        private static String alphaBankJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("id", "alpha-bank");
                root.put("displayName", "Alpha Bank");
                root.put("enabled", true);
                root.put("priority", 50);
                root.put("match", new JSONObject().put("senders", new JSONArray(list("alpha bank", "alphabank", "alpha alerts"))));
                root.put("transforms", new JSONArray(list("normalizeAmount")));
                JSONArray rules = new JSONArray();
                rules.put(regexRule(
                        "card-authorised",
                        "YOUR\\s+CARD\\s+.+?\\*(?<card>\\d{4})\\s+WAS\\s+AUTHORISED\\s+FOR\\s+AN\\s+INDICATIVE\\s+AMOUNT\\s+(?<amount>" + MONEY + ")\\s*(?<currency>EUR|USD|GBP)\\s+ON\\s+\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}\\s+AT\\s+(?<merchant>.+?)\\s*$",
                        list("caseInsensitive"),
                        new JSONObject()
                                .put("paymentMethod", "Credit Card")
                                .put("category", "@keyword")));
                rules.put(regexRule(
                        "placed-transfer",
                        "YOU\\s+HAVE\\s+PLACED\\s+A\\s+TRANSFER\\s+TO\\s+(?<merchant>\\S+)\\s+FOR\\s+(?<amount>" + MONEY + ")\\s*(?<currency>EUR|USD|GBP)\\s+WITH\\s+REF\\s+(?<note>\\S+)\\s*$",
                        list("caseInsensitive"),
                        new JSONObject()
                                .put("paymentMethod", "Electronic Transfer")
                                .put("category", "Transfers")));
                rules.put(amountFallbackRule());
                root.put("rules", rules);
                return root.toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        private static String bankOfCyprusJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("id", "bank-of-cyprus");
                root.put("displayName", "Bank of Cyprus");
                root.put("enabled", true);
                root.put("priority", 30);
                root.put("match", new JSONObject().put("senders", new JSONArray(list("boc message"))));
                root.put("transforms", new JSONArray(list("foldGreek", "normalizeAmount")));
                JSONArray rules = new JSONArray();
                rules.put(regexRule(
                        "card-use",
                        "\u0397\\s+\u039A\u0391\u03A1\u03A4\u0391\\s+\u03A3\u0391\u03A3\\s+[A-Z]+\\*?(?<card>\\d{4})\\s+\u0395\u03A7\u0395\u0399\\s+\u03A7\u03A1\u0397\u03A3\u0399\u039C\u039F\u03A0\u039F\u0399\u0397\u0398\u0395\u0399\\s+\u03A3\u03A4\u039F\\s+(?<merchant>[\\s\\S]+?)\\s+\u03A3\u03A4\u0399\u03A3\\s+\\d{1,2}/\\d{1,2}/\\d{4},\\s+\\d{1,2}:\\d{2}\\s+\u0393\u0399\u0391\\s+\u03A4\u039F\\s+\u0395\u039D\u0394\u0395\u0399\u039A\u03A4\u0399\u039A\u039F\\s+\u03A0\u039F\u03A3\u039F\\s+\\u20AC\\s*(?<amount>[0-9]+(?:[.,][0-9]{2})?)\\.?",
                        list("caseInsensitive", "unicodeCase"),
                        new JSONObject()
                                .put("currency", "EUR")
                                .put("paymentMethod", "Credit Card")
                                .put("category", "@keyword")));
                rules.put(regexRule(
                        "account-debit",
                        "\u039F\\s+\u039B\u039F\u0393[\\s\\S]*?\\d{3,}[\\s\\S]*?\u03A7\u03A1\u0395\u03A9\u0398\u0397\u039A\u0395[\\s\\S]*?\u03A0\u039F\u03A3\u039F\\s+(?:\u03A4\u03A9\u039D\\s+)?(?<currency>EUR|USD|GBP)\\s*(?<amount>[0-9]+(?:[.,][0-9]{2})?)[\\s\\S]*?\u03A0\u0395\u03A1\u0399\u0393\u03A1\u0391\u03A6\u0397\\s*:\\s*(?<merchant>.+?)\\s*$",
                        list("caseInsensitive", "unicodeCase", "multiline"),
                        new JSONObject()
                                .put("paymentMethod", "Electronic Transfer")
                                .put("category", "@accountDebitKeyword")));
                rules.put(regexRule(
                        "incoming-credit",
                        "Ο\\s+ΛΟΓ[\\s\\S]*?\\d{3,}[\\s\\S]*?ΠΙΣΤΩΘΗΚΕ[\\s\\S]*?ΠΟΣΟ\\s+(?:ΤΩΝ\\s+)?(?<currency>EUR|USD|GBP)\\s*(?<amount>" + MONEY + ")[\\s\\S]*?ΠΕΡΙΓΡΑΦΗ\\s*:[\\s\\S]*?\\bBY\\s+(?<merchant>[^>]+)(?:>[^>]+>(?<note>.+?))?\\s*$",
                        list("caseInsensitive", "unicodeCase", "multiline"),
                        new JSONObject()
                                .put("type", "INCOME")
                                .put("paymentMethod", "Electronic Transfer")
                                .put("category", "Income")));
                rules.put(regexRule(
                        "incoming-credit-english",
                        "\\bcredited\\s+with\\s+the\\s+amount\\s+of\\s+(?<currency>EUR|USD|GBP)\\s*(?<amount>" + MONEY + ")[\\s\\S]*?\\bFrom:\\s*(?<merchant>.+?)(?:\\s+Details:\\s*(?<note>.+?))?\\s*$",
                        list("caseInsensitive"),
                        new JSONObject()
                                .put("type", "INCOME")
                                .put("paymentMethod", "Electronic Transfer")
                                .put("category", "Income")));
                rules.put(amountFallbackRule());
                root.put("rules", rules);
                return root.toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        private static String eurobankJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("id", "eurobank");
                root.put("displayName", "Eurobank Cyprus");
                root.put("enabled", true);
                root.put("priority", 40);
                root.put("match", new JSONObject().put("senders", new JSONArray(list("eurobank", "eurobankcy"))));
                root.put("transforms", new JSONArray(list("foldGreek", "normalizeAmount")));
                JSONArray rules = new JSONArray();
                rules.put(regexRule(
                        "card-approved",
                        "\u0397\\s+\u039A\u0391\u03A1\u03A4\u0391\\s+\\*?(?<card>\\d{4})\\s+\u0395\u0393\u039A\u03A1\u0399\u0398\u0397\u039A\u0395\\s+\u0393\u0399\u0391\\s+(?<merchant>.+?)\\s+\\u20AC\\s*(?<amount>[0-9]+(?:[.,][0-9]{2})?)\\s*(?:@\\d{1,2}:\\d{2})?\\s*$",
                        list("unicodeCase"),
                        new JSONObject()
                                .put("currency", "EUR")
                                .put("paymentMethod", "Credit Card")
                                .put("category", "@keyword")));
                rules.put(regexRule(
                        "incoming-credit",
                        "\u039B\u039F\u0393\u0391\u03A1\u0399\u0391\u03A3\u039C\u039F\u03A3[\\s\\S]*?\u03A0\u0399\u03A3\u03A4\u03A9\u0398\u0395\u0399[\\s\\S]*?\u03A0\u039F\u03A3\u039F\\s+\u03A4\u03A9\u039D\\s+(?<amount>" + MONEY + ")\\s*(?<currency>EUR|USD|GBP)",
                        list("caseInsensitive", "unicodeCase"),
                        new JSONObject()
                                .put("type", "INCOME")
                                .put("paymentMethod", "Electronic Transfer")
                                .put("category", "Income")));
                rules.put(amountFallbackRule());
                root.put("rules", rules);
                return root.toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        private static JSONObject regexRule(String name, String pattern, List<String> flags, JSONObject output) throws JSONException {
            return new JSONObject()
                    .put("name", name)
                    .put("type", "regex")
                    .put("pattern", pattern)
                    .put("flags", new JSONArray(flags))
                    .put("output", output);
        }

        private static JSONObject amountFallbackRule() throws JSONException {
            return new JSONObject()
                    .put("name", "unmatched-bank-amount")
                    .put("type", "amountFallback")
                    .put("merchantSource", "empty")
                    .put("noteSource", "body")
                    .put("output", new JSONObject()
                            .put("paymentMethod", "Electronic Transfer")
                            .put("category", "@keyword"));
        }
    }

    private static final class Amount {
        final String currency;
        final String value;

        Amount(String currency, String value) {
            this.currency = currency;
            this.value = value;
        }
    }

    private static final class MultiCurrencyAmount {
        final Amount account;
        final Amount original;

        MultiCurrencyAmount(Amount account, Amount original) {
            this.account = account;
            this.original = original;
        }
    }
}
