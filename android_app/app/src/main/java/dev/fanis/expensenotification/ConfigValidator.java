package dev.fanis.expensenotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ConfigValidator {
    private static final Set<String> RULE_TYPES = set("regex", "amountFallback");
    private static final Set<String> FLAGS = set("caseInsensitive", "unicodeCase", "multiline", "dotall");
    private static final Set<String> TRANSFORMS = set("foldGreek", "normalizeAmount", "multiCurrency");
    private static final Set<String> MERCHANT_SOURCES = set("", "empty", "title", "firstNonNoiseLine");
    private static final Set<String> NOTE_SOURCES = set("", "body");

    private ConfigValidator() {
    }

    static Result validate(String dirName, String fileName, String json) {
        if ("inputs".equals(dirName)) {
            return validateInput(fileName, json);
        }
        if ("outputs".equals(dirName)) {
            return validateOutput(fileName, json);
        }
        Result result = new Result();
        result.error("Unknown config directory: " + dirName);
        return result;
    }

    static Result validateInput(String fileName, String json) {
        Result result = new Result();
        JSONObject root = parse(json, result);
        if (root == null) {
            return result;
        }
        require(root, "id", result);
        validateArrayStrings(root.optJSONArray("transforms"), TRANSFORMS, "transforms", result);

        JSONObject match = root.optJSONObject("match");
        if (match != null) {
            validateStringArray(match.optJSONArray("packages"), "match.packages", result);
            validateStringArray(match.optJSONArray("senders"), "match.senders", result);
        }

        JSONArray rules = root.optJSONArray("rules");
        if (rules == null || rules.length() == 0) {
            result.error("rules must contain at least one rule");
        } else {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null) {
                    result.error("rules[" + i + "] must be an object");
                    continue;
                }
                validateRule(rule, i, result);
            }
        }

        JSONArray tests = root.optJSONArray("tests");
        if (tests != null) {
            validateTests(tests, result);
            ConfigSelfTest.Result testResult = ConfigSelfTest.runInputConfig(fileName, json);
            if (!testResult.passed()) {
                for (String failure : testResult.failures) {
                    result.error("embedded test: " + failure);
                }
            }
        } else {
            result.warning("No embedded tests array.");
        }
        return result;
    }

    static Result validateOutput(String fileName, String json) {
        Result result = new Result();
        JSONObject root = parse(json, result);
        if (root == null) {
            return result;
        }
        require(root, "id", result);
        require(root, "package", result);
        require(root, "activity", result);
        if (root.optJSONObject("fieldMap") == null) {
            result.warning("fieldMap missing; built-in extra names will be used.");
        }
        String format = root.optString("dateFormat", "yyyy-MM-dd");
        try {
            new SimpleDateFormat(format, Locale.US);
        } catch (IllegalArgumentException e) {
            result.error("dateFormat is invalid: " + e.getMessage());
        }
        try {
            OutputProfile.fromConfigJson(json);
        } catch (JSONException e) {
            result.error(fileName + " could not be loaded as an output profile: " + e.getMessage());
        }
        return result;
    }

    private static void validateRule(JSONObject rule, int index, Result result) {
        String prefix = "rules[" + index + "]";
        String name = rule.optString("name", "");
        if (name.isEmpty()) {
            result.warning(prefix + " has no name");
        }
        String type = rule.optString("type", "regex");
        if (!RULE_TYPES.contains(type)) {
            result.error(prefix + " has unknown type: " + type);
            return;
        }
        validateArrayStrings(rule.optJSONArray("flags"), FLAGS, prefix + ".flags", result);
        validateArrayStrings(rule.optJSONArray("transforms"), TRANSFORMS, prefix + ".transforms", result);
        validateScalar(rule.optString("merchantSource", ""), MERCHANT_SOURCES, prefix + ".merchantSource", result);
        validateScalar(rule.optString("noteSource", ""), NOTE_SOURCES, prefix + ".noteSource", result);
        if ("regex".equals(type)) {
            String pattern = rule.optString("pattern", "");
            if (pattern.isEmpty()) {
                result.error(prefix + " regex rule is missing pattern");
            } else {
                try {
                    Pattern.compile(pattern, flags(rule.optJSONArray("flags")));
                } catch (PatternSyntaxException e) {
                    result.error(prefix + " regex does not compile: " + e.getDescription());
                }
                if (!pattern.contains("?<amount>")) {
                    result.error(prefix + " regex must define a named amount group: (?<amount>...)");
                }
            }
        }
    }

    private static void validateTests(JSONArray tests, Result result) {
        for (int i = 0; i < tests.length(); i++) {
            JSONObject test = tests.optJSONObject(i);
            if (test == null) {
                result.error("tests[" + i + "] must be an object");
                continue;
            }
            if (test.optString("body", "").isEmpty() && test.optString("title", "").isEmpty()) {
                result.error("tests[" + i + "] must include title or body");
            }
            JSONObject expect = test.optJSONObject("expect");
            if (expect == null) {
                result.warning("tests[" + i + "] has no expect object");
            }
        }
    }

    private static JSONObject parse(String json, Result result) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            result.error("Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private static void require(JSONObject root, String field, Result result) {
        if (root.optString(field, "").isEmpty()) {
            result.error("Missing required field: " + field);
        }
    }

    private static void validateStringArray(JSONArray values, String path, Result result) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.length(); i++) {
            if (values.optString(i, "").isEmpty()) {
                result.error(path + "[" + i + "] must be a non-empty string");
            }
        }
    }

    private static void validateArrayStrings(JSONArray values, Set<String> allowed, String path, Result result) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.length(); i++) {
            validateScalar(values.optString(i, ""), allowed, path + "[" + i + "]", result);
        }
    }

    private static void validateScalar(String value, Set<String> allowed, String path, Result result) {
        if (value.isEmpty()) {
            return;
        }
        if (!allowed.contains(value)) {
            result.error(path + " has unknown value: " + value);
        }
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

    private static Set<String> set(String... values) {
        HashSet<String> set = new HashSet<>();
        for (String value : values) {
            set.add(value);
        }
        return set;
    }

    static final class Result {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        boolean ok() {
            return errors.isEmpty();
        }

        void error(String message) {
            errors.add(message);
        }

        void warning(String message) {
            warnings.add(message);
        }

        String summary() {
            StringBuilder builder = new StringBuilder();
            if (errors.isEmpty()) {
                builder.append("Valid.");
            } else {
                builder.append("Invalid:");
                for (String error : errors) {
                    builder.append("\n").append(error);
                }
            }
            if (!warnings.isEmpty()) {
                builder.append("\nWarnings:");
                for (String warning : warnings) {
                    builder.append("\n").append(warning);
                }
            }
            return builder.toString();
        }
    }
}
