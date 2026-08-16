package dev.fanis.expensenotification;

import java.util.List;

/**
 * Flags a candidate that looks like the same payment captured through another
 * source, e.g. a PayPal receipt email next to the card notification of the
 * charge that funded it. Only a hint for the reviewer: nothing is suppressed,
 * because two genuinely distinct charges can share an amount and a day.
 */
final class DuplicateHint {
    // Different sources see the same payment minutes to hours apart; an email
    // receipt can trail the card notification considerably.
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    private DuplicateHint() {
    }

    /** Name of another app that captured the same amount nearby in time, or "". */
    static String otherSource(Candidate candidate, List<Candidate> all) {
        if (candidate.amount == null || candidate.amount.isEmpty()) {
            return "";
        }
        for (Candidate other : all) {
            if (other.id == candidate.id
                    || safe(other.packageName).equals(safe(candidate.packageName))
                    || !safe(other.amount).equals(candidate.amount)
                    || !safe(other.currency).equalsIgnoreCase(safe(candidate.currency))
                    || Math.abs(other.postedAt - candidate.postedAt) > WINDOW_MS) {
                continue;
            }
            return safe(other.appName).isEmpty() ? safe(other.packageName) : other.appName;
        }
        return "";
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
