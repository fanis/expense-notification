package dev.fanis.expensenotification;

/**
 * The combined title+body of one notification, with the Greek-folded variant
 * computed at most once no matter how many fold-aware rules inspect it.
 */
final class NotificationText {
    final String combined;
    private String folded;

    NotificationText(String combined) {
        this.combined = combined;
    }

    String folded() {
        if (folded == null) {
            folded = ExpenseParser.foldAmbiguousGreek(combined);
        }
        return folded;
    }

    /**
     * Splits one stacked line of an expanded summary notification (InboxStyle)
     * into {sender, content}, or null when the line has no sender prefix. Email
     * apps render these lines as "Sender<separator>Subject and preview": Gmail
     * uses U+2002 (en space) as the separator; other apps use other typographic
     * spaces or a run of plain spaces.
     */
    static String[] splitStackedLine(String line) {
        String safe = line == null ? "" : line;
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            boolean typographicSpace = c == '\u2002' || c == '\u2003' || c == '\u2009' || c == '\u00A0';
            boolean doubleSpace = c == ' ' && i + 1 < safe.length() && safe.charAt(i + 1) == ' ';
            if (!typographicSpace && !doubleSpace) {
                continue;
            }
            String sender = safe.substring(0, i).trim();
            // trim() only strips chars up to U+0020, so walk past the typographic
            // separator (and any surrounding plain spaces) explicitly.
            int contentStart = i;
            while (contentStart < safe.length() && isSeparator(safe.charAt(contentStart))) {
                contentStart++;
            }
            String content = safe.substring(contentStart).trim();
            if (!sender.isEmpty() && !content.isEmpty()) {
                return new String[]{sender, content};
            }
        }
        return null;
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\u2002' || c == '\u2003' || c == '\u2009' || c == '\u00A0';
    }
}
