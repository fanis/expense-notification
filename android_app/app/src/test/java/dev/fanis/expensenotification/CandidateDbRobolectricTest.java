package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class CandidateDbRobolectricTest {

    private Context context;
    private CandidateDb db;

    @Before
    public void setUp() {
        CandidateDb.resetInstanceForTesting();
        context = RuntimeEnvironment.getApplication();
        db = CandidateDb.getInstance(context);
    }

    @After
    public void tearDown() {
        CandidateDb.resetInstanceForTesting();
    }

    private static Candidate candidate(String key, long postedAt) {
        Candidate candidate = new Candidate();
        candidate.notificationKey = key;
        candidate.packageName = "com.revolut.revolut";
        candidate.appName = "Revolut";
        candidate.title = "SAMPLE UTILITY";
        candidate.text = "You spent €33.95\nEUR balance: €543.21";
        candidate.merchant = "SAMPLE UTILITY";
        candidate.amount = "33.95";
        candidate.currency = "EUR";
        candidate.suggestedCategory = "";
        candidate.suggestedPaymentMethod = "Credit Card";
        candidate.postedAt = postedAt;
        candidate.status = Candidate.STATUS_NEW;
        return candidate;
    }

    @Test
    public void duplicateNotificationKeyIsIgnored() {
        assertNotEquals(-1, db.insertIfNew(candidate("key-1", 1000L)));
        assertEquals(-1, db.insertIfNew(candidate("key-1", 1000L)));
        assertEquals(1, db.listAll().size());
    }

    @Test
    public void reparsesStoredSmsOnceAfterConfigRevisionChanges() {
        Candidate stale = candidate("key-1", 1000L);
        stale.merchant = "STALE MERCHANT";
        stale.amount = "999.99";
        assertNotEquals(-1, db.insertIfNew(stale));

        // Same revision: the stored fields are returned untouched.
        assertEquals("STALE MERCHANT", db.listAll().get(0).merchant);

        // After a config change the stored SMS is re-parsed with the current parser
        // and the corrected fields are written back.
        ConfigRevision.bump(context);
        Candidate refreshed = db.listAll().get(0);
        assertEquals("SAMPLE UTILITY", refreshed.merchant);
        assertEquals("33.95", refreshed.amount);
        assertEquals(Candidate.STATUS_NEW, refreshed.status);
    }

    @Test
    public void prunesOldSettledCandidatesButKeepsNewOnes() {
        long old = System.currentTimeMillis() - CandidateDb.SETTLED_RETENTION_MS - 1000L;
        long settledId = db.insertIfNew(candidate("old-processed", old));
        assertTrue(settledId != -1);
        assertNotEquals(-1, db.insertIfNew(candidate("old-new", old + 1)));
        db.mark(settledId, Candidate.STATUS_PROCESSED);

        assertEquals(1, db.pruneSettled());
        List<Candidate> remaining = db.listAll();
        assertEquals(1, remaining.size());
        assertEquals("old-new", remaining.get(0).notificationKey);
    }
}
