package dev.fanis.expensenotification;

import java.math.BigDecimal;

final class Candidate {
    static final String STATUS_NEW = "NEW";
    static final String STATUS_SKIPPED = "SKIPPED";
    static final String STATUS_PROCESSED = "PROCESSED";
    static final String TYPE_EXPENSE = "EXPENSE";
    static final String TYPE_INCOME = "INCOME";

    long id;
    String notificationKey;
    String packageName;
    String appName;
    String title;
    String text;
    String merchant;
    String amount;
    String currency;
    String originalAmount;
    String originalCurrency;
    String suggestedCategory;
    String suggestedPaymentMethod;
    String note = "";
    String transactionType = TYPE_EXPENSE;
    long postedAt;
    String status;

    boolean isIncome() {
        return TYPE_INCOME.equals(transactionType);
    }

    boolean hasAmount() {
        try {
            new BigDecimal(amount);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean hasOriginalAmount() {
        if (originalAmount == null || originalAmount.isEmpty()) {
            return false;
        }
        try {
            new BigDecimal(originalAmount);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean hasMultipleCurrencies() {
        return hasOriginalAmount() && originalCurrency != null && !originalCurrency.isEmpty()
                && (currency == null || !originalCurrency.equalsIgnoreCase(currency));
    }

    String amountLine() {
        return formatLine(currency, amount);
    }

    String originalAmountLine() {
        return formatLine(originalCurrency, originalAmount);
    }

    private static String formatLine(String currency, String amount) {
        if (amount == null || amount.isEmpty()) {
            return "";
        }
        if (currency == null || currency.isEmpty()) {
            return amount;
        }
        return currency + " " + amount;
    }
}
