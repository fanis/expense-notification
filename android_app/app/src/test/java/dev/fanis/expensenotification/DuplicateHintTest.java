package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DuplicateHintTest {

    private static Candidate candidate(long id, String packageName, String appName, String amount,
                                       String currency, long postedAt) {
        Candidate candidate = new Candidate();
        candidate.id = id;
        candidate.packageName = packageName;
        candidate.appName = appName;
        candidate.amount = amount;
        candidate.currency = currency;
        candidate.postedAt = postedAt;
        return candidate;
    }

    @Test
    public void flagsSameAmountFromAnotherSourceNearbyInTime() {
        Candidate paypal = candidate(1, "com.google.android.gm", "Gmail", "12.34", "USD", 1_000_000);
        Candidate card = candidate(2, "com.revolut.revolut", "Revolut", "12.34", "USD", 1_000_000 + 3_600_000);
        List<Candidate> all = Arrays.asList(paypal, card);
        assertEquals("Revolut", DuplicateHint.otherSource(paypal, all));
        assertEquals("Gmail", DuplicateHint.otherSource(card, all));
    }

    @Test
    public void ignoresSamePackageDifferentAmountOrCurrencyOrDistantTime() {
        Candidate paypal = candidate(1, "com.google.android.gm", "Gmail", "12.34", "USD", 1_000_000);
        Candidate samePackage = candidate(2, "com.google.android.gm", "Gmail", "12.34", "USD", 1_000_000);
        Candidate otherAmount = candidate(3, "com.revolut.revolut", "Revolut", "99.00", "USD", 1_000_000);
        Candidate otherCurrency = candidate(4, "com.revolut.revolut", "Revolut", "12.34", "EUR", 1_000_000);
        Candidate distant = candidate(5, "com.revolut.revolut", "Revolut", "12.34", "USD",
                1_000_000 + DuplicateHint.WINDOW_MS + 1);
        assertEquals("", DuplicateHint.otherSource(paypal,
                Arrays.asList(paypal, samePackage, otherAmount, otherCurrency, distant)));
    }

    @Test
    public void emptyAmountIsNeverFlagged() {
        Candidate blank = candidate(1, "com.google.android.gm", "Gmail", "", "USD", 1_000_000);
        Candidate other = candidate(2, "com.revolut.revolut", "Revolut", "", "USD", 1_000_000);
        assertEquals("", DuplicateHint.otherSource(blank, Arrays.asList(blank, other)));
    }
}
