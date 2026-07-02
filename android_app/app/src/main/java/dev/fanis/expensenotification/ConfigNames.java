package dev.fanis.expensenotification;

/** Normalizes user-supplied config file names into safe "<name>.json" form. */
final class ConfigNames {
    private ConfigNames() {
    }

    static String cleanJsonName(String name) {
        String cleaned = name == null ? "config.json" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (cleaned.isEmpty()) {
            cleaned = "config.json";
        }
        return cleaned.endsWith(".json") ? cleaned : cleaned + ".json";
    }

    // Decodes backslash-u-XXXX escapes so bundled configs (kept ASCII-safe in the
    // repo) show their Greek text readably in the editor. An escape whose backslash
    // is itself escaped (double backslash before the 'u', e.g. a regex-level unicode
    // escape inside a JSON string) is literal text and must be left untouched.
    static String decodeUnicodeEscapes(String text) {
        if (text == null || text.indexOf("\\u") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < text.length() && text.charAt(j) == '\\') {
                j++;
            }
            int run = j - i;
            // Only an odd trailing backslash can start a real unicode escape.
            if (run % 2 == 1 && j + 4 < text.length() && text.charAt(j) == 'u'
                    && isHex(text, j + 1, j + 5)) {
                for (int k = 0; k < run - 1; k++) {
                    out.append('\\');
                }
                out.append((char) Integer.parseInt(text.substring(j + 1, j + 5), 16));
                i = j + 5;
                continue;
            }
            for (int k = 0; k < run; k++) {
                out.append('\\');
            }
            i = j;
        }
        return out.toString();
    }

    private static boolean isHex(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
