package dev.fanis.expensenotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ConfigSelfTest {
    private ConfigSelfTest() {
    }

    static Result runInputConfig(String fileName, String json) {
        ArrayList<String> failures = new ArrayList<>();
        int count = 0;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray tests = root.optJSONArray("tests");
            if (tests == null) {
                return new Result(0, failures);
            }
            for (int i = 0; i < tests.length(); i++) {
                JSONObject test = tests.optJSONObject(i);
                if (test == null) {
                    continue;
                }
                count++;
                runOne(fileName, json, root, test, i, failures);
            }
        } catch (Exception e) {
            failures.add("Could not run config tests: " + e.getMessage());
        }
        return new Result(count, failures);
    }

    private static void runOne(String fileName, String json, JSONObject root, JSONObject test, int index,
                               ArrayList<String> failures) throws JSONException {
        String name = test.optString("name", "test " + (index + 1));
        JSONObject match = root.optJSONObject("match");
        String packageName = test.optString("package", first(match == null ? null : match.optJSONArray("packages"), "com.textra"));
        String appName = test.optString("app", packageName);
        String title = test.optString("title", first(match == null ? null : match.optJSONArray("senders"), ""));
        String body = test.optString("body", "");
        Candidate candidate = ExpenseParser.parseInputConfig(
                fileName, json, packageName, appName, "config-test-" + index, 0L, title, body);

        JSONObject expect = test.optJSONObject("expect");
        boolean shouldMatch = expect == null || expect.optBoolean("matched", true);
        if (!shouldMatch) {
            if (candidate != null) {
                failures.add(name + ": expected no match, got amount " + candidate.amountLine());
            }
            return;
        }
        if (candidate == null) {
            failures.add(name + ": expected a match");
            return;
        }
        if (expect == null) {
            return;
        }
        compare(name, "amount", expect, candidate.amount, failures);
        compare(name, "currency", expect, candidate.currency, failures);
        compare(name, "merchant", expect, candidate.merchant, failures);
        compare(name, "category", expect, candidate.suggestedCategory, failures);
        compare(name, "paymentMethod", expect, candidate.suggestedPaymentMethod, failures);
        compare(name, "type", expect, candidate.transactionType, failures);
        compare(name, "note", expect, candidate.note, failures);
    }

    private static String first(JSONArray values, String fallback) {
        if (values == null || values.length() == 0) {
            return fallback;
        }
        return values.optString(0, fallback);
    }

    private static void compare(String testName, String field, JSONObject expect, String actual, ArrayList<String> failures) {
        if (!expect.has(field)) {
            return;
        }
        String expected = expect.optString(field, "");
        if (!expected.equals(actual == null ? "" : actual)) {
            failures.add(testName + ": expected " + field + " " + expected + ", got " + (actual == null ? "" : actual));
        }
    }

    static final class Result {
        final int count;
        final List<String> failures;

        Result(int count, List<String> failures) {
            this.count = count;
            this.failures = failures;
        }

        boolean passed() {
            return failures.isEmpty();
        }

        String summary() {
            if (count == 0) {
                return "No embedded tests.";
            }
            if (failures.isEmpty()) {
                return "Passed " + count + " embedded test" + (count == 1 ? "" : "s") + ".";
            }
            StringBuilder builder = new StringBuilder("Failed ").append(failures.size())
                    .append(" of ").append(count).append(" embedded tests:");
            for (String failure : failures) {
                builder.append("\n").append(failure);
            }
            return builder.toString();
        }
    }
}
