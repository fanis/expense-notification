package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class DedupeKeyTest {

    @Test
    public void sameNotificationKeyAndBodyDedupeToSameKey() {
        assertEquals(
                ExpenseNotificationListener.dedupeKey("0|com.textra|1|null|10000", "You spent €5.00"),
                ExpenseNotificationListener.dedupeKey("0|com.textra|1|null|10000", "You spent €5.00"));
    }

    @Test
    public void differentBodiesUnderSameNotificationKeyStayDistinct() {
        // Messaging apps reuse one conversation notification (same sbn key) for every
        // SMS from a sender; each distinct body must produce a distinct candidate key.
        String key = "0|com.textra|1|null|10000";
        assertNotEquals(
                ExpenseNotificationListener.dedupeKey(key, "You spent €5.00"),
                ExpenseNotificationListener.dedupeKey(key, "You spent €6.00"));
    }

    @Test
    public void nullBodyIsAccepted() {
        assertEquals(
                ExpenseNotificationListener.dedupeKey("k", null),
                ExpenseNotificationListener.dedupeKey("k", ""));
    }
}
