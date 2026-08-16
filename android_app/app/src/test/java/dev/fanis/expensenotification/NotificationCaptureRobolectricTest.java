package dev.fanis.expensenotification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
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

    private static List<ExpenseNotificationListener.StackedMessage> lines(String... texts) {
        ArrayList<ExpenseNotificationListener.StackedMessage> items = new ArrayList<>();
        for (String text : texts) {
            items.add(new ExpenseNotificationListener.StackedMessage("", text, 0L));
        }
        return items;
    }

    // ---- Capture of stacked summaries. ----

    @Test
    public void emailDigestLineIsCapturedViaItsOwnSender() {
        // A digest summary: the title names no sender, the watched sender sits inside
        // the stacked line. The unrelated chat line must not produce a candidate.
        List<ExpenseNotificationListener.StackedMessage> lines = lines(
                "PayPal\u2002You paid $12,34 USD to SAMPLE MERCHANT INC",
                "SAMPLE FRIEND\u2002Lunch tomorrow?");
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
        List<ExpenseNotificationListener.StackedMessage> lines = lines("PayPal\u2002You paid $12,34 USD to SAMPLE MERCHANT INC");
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
        List<ExpenseNotificationListener.StackedMessage> lines = lines(older, newest);
        int saved = ExpenseNotificationListener.capture(context, "com.textra", "Textra",
                "conversation-key", POSTED_AT, "Alpha Bank", newest, lines);
        assertEquals(2, saved);
        assertEquals(2, CandidateDb.getInstance(context).listAll().size());

        assertEquals(0, ExpenseNotificationListener.capture(context, "com.textra", "Textra",
                "conversation-key", POSTED_AT, "Alpha Bank", newest, lines));
    }

    // ---- MessagingStyle (EXTRA_MESSAGES) summaries. ----

    @Test
    public void readsMessagingStyleMessagesFromExtras() {
        Bundle message = new Bundle();
        message.putCharSequence("text", "YOU HAVE PLACED A TRANSFER TO SAMPLE****000 FOR 300,00EUR WITH REF REF0000001");
        message.putCharSequence("sender", "Alpha Bank");
        message.putLong("time", POSTED_AT - 3_600_000);
        Bundle extras = new Bundle();
        extras.putParcelableArray(Notification.EXTRA_MESSAGES, new Parcelable[]{message});

        List<ExpenseNotificationListener.StackedMessage> stacked = ExpenseNotificationListener.stackedMessages(extras);
        assertEquals(1, stacked.size());
        assertEquals("Alpha Bank", stacked.get(0).sender);
        assertEquals(POSTED_AT - 3_600_000, stacked.get(0).time);
    }

    @Test
    public void messagingStyleHistoryIsRecoveredWithItsOwnSenderAndTime() {
        // The message's own sender matches the source even when the conversation
        // notification is titled by a contact alias, and the recovered candidate is
        // dated to when the message arrived, not to when the summary was re-posted.
        String missed = "YOU HAVE PLACED A TRANSFER TO SAMPLE****000 FOR 300,00EUR WITH REF REF0000001";
        List<ExpenseNotificationListener.StackedMessage> stacked = new ArrayList<>();
        stacked.add(new ExpenseNotificationListener.StackedMessage("Alpha Bank", missed, POSTED_AT - 3_600_000));

        int saved = ExpenseNotificationListener.capture(context, "com.textra", "Textra",
                "conversation-key", POSTED_AT, "Bank (work)", "Unrelated newest message", stacked);
        assertEquals(1, saved);
        List<Candidate> all = CandidateDb.getInstance(context).listAll();
        assertEquals(1, all.size());
        assertEquals("300.00", all.get(0).amount);
        assertEquals(POSTED_AT - 3_600_000, all.get(0).postedAt);
    }
}
