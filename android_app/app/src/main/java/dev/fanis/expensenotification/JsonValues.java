package dev.fanis.expensenotification;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** JSON array/scalar coercion helpers shared by the config parser and validator. */
final class JsonValues {
    private JsonValues() {
    }

    static List<String> strings(JSONArray json, List<String> fallback) {
        if (json == null) {
            return new ArrayList<>(fallback);
        }
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            result.add(json.optString(i));
        }
        return result;
    }

    static Set<String> stringSet(JSONArray json, Set<String> fallback) {
        if (json == null) {
            return new HashSet<>(fallback);
        }
        return new HashSet<>(strings(json, Collections.emptyList()));
    }

    static Set<String> lowerSet(List<String> values) {
        HashSet<String> set = new HashSet<>();
        for (String value : values) {
            set.add(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    /** Maps config flag names to java.util.regex compile flags. */
    static int patternFlags(JSONArray json) {
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
}
