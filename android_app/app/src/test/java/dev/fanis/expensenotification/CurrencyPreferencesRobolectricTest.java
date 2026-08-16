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

@RunWith(RobolectricTestRunner.class)
public class CurrencyPreferencesRobolectricTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void homeCurrencyDefaultsToUnset() {
        assertEquals("", CurrencyPreferences.homeCurrency(context));
        // Without a home currency nothing is foreign, so no badge ever shows.
        assertFalse(CurrencyPreferences.isForeign(context, "USD"));
    }

    @Test
    public void homeCurrencyIsNormalizedToUppercaseIso() {
        CurrencyPreferences.setHomeCurrency(context, " eur ");
        assertEquals("EUR", CurrencyPreferences.homeCurrency(context));
    }

    @Test
    public void invalidHomeCurrencyClearsTheSetting() {
        CurrencyPreferences.setHomeCurrency(context, "EUR");
        CurrencyPreferences.setHomeCurrency(context, "EURO");
        assertEquals("", CurrencyPreferences.homeCurrency(context));
    }

    @Test
    public void foreignComparisonIsCaseInsensitiveAndIgnoresBlanks() {
        CurrencyPreferences.setHomeCurrency(context, "EUR");
        assertTrue(CurrencyPreferences.isForeign(context, "USD"));
        assertFalse(CurrencyPreferences.isForeign(context, "EUR"));
        assertFalse(CurrencyPreferences.isForeign(context, "eur"));
        // A candidate with no captured currency can't be judged foreign.
        assertFalse(CurrencyPreferences.isForeign(context, ""));
        assertFalse(CurrencyPreferences.isForeign(context, null));
    }
}
