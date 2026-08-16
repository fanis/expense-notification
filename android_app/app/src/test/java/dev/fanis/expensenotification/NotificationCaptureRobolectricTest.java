package dev.fanis.expensenotification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NotificationCaptureRobolectricTest {

    private static final long POSTED_AT = 1_700_000_000_000L;

    private Context context;

    @Before
    public void setUp() {
        CandidateDb.resetInstanceForTesting();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        CandidateDb.resetInstanceForTesting();
    }

    // ---- Stacked line splitting. ----

    @Test
    public void splitsGmailDigestLineOnEnSpace() {
        assertArrayEquals(new String[]{"PayPal", "Receipt for Your Payment"},
                NotificationText.splitStackedLine("PayPal\u2002Receipt for Your Payment"));
    }

    @Test
    public void splitsOnDoubleSpaceButNotSingleSpace() {
        assertArrayEquals(new String[]{"PayPal", "Receipt"},
                NotificationText.splitStackedLine("PayPal  Receipt"));
        assertNull(NotificationText.splitStackedLine("Just a plain sentence"));
    }

    @Test
    public void splitRequiresContentOnBothSides() {
        assertNull(NotificationText.splitStackedLine("\u2002leading separator"));
        assertNull(NotificationText.splitStackedLine(""));
        assertNull(NotificationText.splitStackedLine(null));
    }

    // ---- Capture of stacked summaries. ----

    @Test
    public void emailDigestLineIsCapturedViaItsOwnSender() {
        // A digest summary: the title names no sender, the watched sender sits inside
        // the stacked line. The unrelated chat line must not produce a candidate.
        CharSequence[] lines = {
                "PayPal\u2002You paid $12,34 USD to SAMPLE MERCHANT INC",
                "SAMPLE FRIEND\u2002Lunch tomorrow?",
        };
        int saved = ExpenseNotificationListener.capture(context, "com.google.android.gm", "Gmail",
                "digest-key", POSTED_AT, "2 new messages", "account@example.com", lines);
        assertEquals(1, saved);
        List<Candidate> all = CandidateDb.getInstance(context).listAll();
        assertEquals(1, all.size());
        assertEquals("SAMPLE MERCHANT INC", all.get(0).merchant);
        assertEquals("PayPal", all.get(0).suggestedPaymentMethod);

        // Re-scanning the same still-active summary must not duplicate.
        assertEquals(0, ExpenseNotificationListener.capture(context, "com.google.android.gm", "Gmail",
                "digest-key", POSTED_AT, "2 new messages", "account@example.com", lines));
    }

    @Test
    public void digestLineDoesNotDuplicateTheReceiptsOwnNotification() {
        // The receipt arrives as its own notification first...
        int saved = ExpenseNotificationListener.capture(context, "com.google.android.gm", "Gmail",
                "child-key", POSTED_AT, "PayPal",
                "Receipt for Your Payment to SAMPLE MERCHANT INC\nYou paid $12,34 USD to SAMPLE MERCHANT INC", null);
        assertEquals(1, saved);

        // ...then again as a line of the digest summary, under a different key and
        // with different text. The equivalence check must keep it out.
        CharSequence[] lines = {"PayPal\u2002You paid $12,34 USD to SAMPLE MERCHANT INC"};
        assertEquals(0, ExpenseNotificationListener.capture(context, "com.google.android.gm", "Gmail",
                "digest-key", POSTED_AT + 60_000, "2 new messages", "account@example.com", lines));
        assertEquals(1, CandidateDb.getInstance(context).listAll().size());
    }

    @Test
    public void smsHistoryLinesRecoverMissedMessages() {
        // Conversation summary: the body carries the newest SMS, the stacked lines
        // carry the history. The older transfer was missed while the listener was
        // down and must be recovered from its line; the newest one must be captured
        // once via the body, not once more via its line.
        String older = "YOU HAVE PLACED A TRANSFER TO SAMPLE****000 FOR 300,00EUR WITH REF REF0000001";
        String newest = "YOU HAVE PLACED A TRANSFER TO SAMPLE****000 FOR 100,00EUR WITH REF REF0000002";
        CharSequence[] lines = {older, newest};
        int saved = ExpenseNotificationListener.capture(context, "com.textra", "Textra",
                "conversation-key", POSTED_AT, "Alpha Bank", newest, lines);
        assertEquals(2, saved);
        assertEquals(2, CandidateDb.getInstance(context).listAll().size());

        assertEquals(0, ExpenseNotificationListener.capture(context, "com.textra", "Textra",
                "conversation-key", POSTED_AT, "Alpha Bank", newest, lines));
    }
}
