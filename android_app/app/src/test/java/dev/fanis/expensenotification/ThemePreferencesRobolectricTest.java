package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ThemePreferencesRobolectricTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void defaultsToAuto() {
        assertEquals(ThemePreferences.MODE_AUTO, ThemePreferences.mode(context));
    }

    @Test
    public void persistsSelectedMode() {
        ThemePreferences.setMode(context, ThemePreferences.MODE_DARK);
        assertEquals(ThemePreferences.MODE_DARK, ThemePreferences.mode(context));

        ThemePreferences.setMode(context, ThemePreferences.MODE_LIGHT);
        assertEquals(ThemePreferences.MODE_LIGHT, ThemePreferences.mode(context));
    }

    @Test
    public void unknownStoredValueFallsBackToAuto() {
        ThemePreferences.setMode(context, "PURPLE");
        assertEquals(ThemePreferences.MODE_AUTO, ThemePreferences.mode(context));
    }

    @Test
    public void forcedNightBitsMatchModes() {
        assertEquals(0, ThemePreferences.forcedNightBits(ThemePreferences.MODE_AUTO));
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                ThemePreferences.forcedNightBits(ThemePreferences.MODE_LIGHT));
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
                ThemePreferences.forcedNightBits(ThemePreferences.MODE_DARK));
    }

    @Test
    public void labelsAreHumanReadable() {
        assertEquals("Auto (follow system)", ThemePreferences.label(ThemePreferences.MODE_AUTO));
        assertEquals("Light", ThemePreferences.label(ThemePreferences.MODE_LIGHT));
        assertEquals("Dark", ThemePreferences.label(ThemePreferences.MODE_DARK));
    }
}
