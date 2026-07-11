package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutputProfileTest {

    @Test
    public void defaultsCarryDateAndTimeExtras() {
        OutputProfile profile = OutputProfile.defaults();
        assertEquals("date", profile.dateExtra());
        assertEquals("time", profile.timeExtra());
        assertEquals("yyyy-MM-dd", profile.dateFormat);
        assertEquals("HH:mm", profile.timeFormat);
    }

    @Test
    public void configCanRemapTimeExtraAndFormat() throws Exception {
        OutputProfile profile = OutputProfile.fromConfigJson("{"
                + "\"id\":\"custom\",\"package\":\"dev.x\",\"activity\":\"dev.x.Add\","
                + "\"fieldMap\":{\"time\":\"tx_time\"},"
                + "\"timeFormat\":\"HH:mm:ss\""
                + "}");
        assertEquals("tx_time", profile.timeExtra());
        assertEquals("HH:mm:ss", profile.timeFormat);
    }

    @Test
    public void missingTimeConfigFallsBackToDefaults() throws Exception {
        OutputProfile profile = OutputProfile.fromConfigJson(
                "{\"id\":\"custom\",\"package\":\"dev.x\",\"activity\":\"dev.x.Add\"}");
        assertEquals("time", profile.timeExtra());
        assertEquals("HH:mm", profile.timeFormat);
    }
}
