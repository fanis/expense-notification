package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class QueuePreferencesRobolectricTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void defaultsToShowingEverything() {
        assertFalse(QueuePreferences.onlyUnprocessed(context));
    }

    @Test
    public void persistsFilterChoice() {
        QueuePreferences.setOnlyUnprocessed(context, true);
        assertTrue(QueuePreferences.onlyUnprocessed(context));
        QueuePreferences.setOnlyUnprocessed(context, false);
        assertFalse(QueuePreferences.onlyUnprocessed(context));
    }

    @Test
    public void unprocessedFilterKeepsOnlyNewCandidates() {
        Candidate fresh = withStatus(Candidate.STATUS_NEW);
        Candidate processed = withStatus(Candidate.STATUS_PROCESSED);
        Candidate skipped = withStatus(Candidate.STATUS_SKIPPED);
        List<Candidate> filtered = MainActivity.unprocessedOnly(Arrays.asList(processed, fresh, skipped));
        assertEquals(1, filtered.size());
        assertEquals(Candidate.STATUS_NEW, filtered.get(0).status);
    }

    private static Candidate withStatus(String status) {
        Candidate candidate = new Candidate();
        candidate.status = status;
        return candidate;
    }
}
