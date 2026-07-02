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
}
