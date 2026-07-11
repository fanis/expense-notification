package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutputProfileTest {

    @Test
    public void defaultsCarryDateAndDateTimeMillisExtras() {
        OutputProfile profile = OutputProfile.defaults();
        assertEquals("date", profile.dateExtra());
        assertEquals("yyyy-MM-dd", profile.dateFormat);
        // Expense Manager reads a long epoch-millis "dateLong" extra for date+time.
        assertEquals("dateLong", profile.dateTimeMillisExtra);
    }

    @Test
    public void configCanSetDateTimeMillisExtraName() throws Exception {
        OutputProfile profile = OutputProfile.fromConfigJson("{"
                + "\"id\":\"custom\",\"package\":\"dev.x\",\"activity\":\"dev.x.Add\","
                + "\"dateTimeMillisExtra\":\"txMillis\""
                + "}");
        assertEquals("txMillis", profile.dateTimeMillisExtra);
    }

    @Test
    public void dateTimeMillisExtraDefaultsEmptyForGenericConfigs() throws Exception {
        // A config that does not opt in gets no long extra, so unknown apps are not
        // sent a "dateLong" they never asked for.
        OutputProfile profile = OutputProfile.fromConfigJson(
                "{\"id\":\"custom\",\"package\":\"dev.x\",\"activity\":\"dev.x.Add\"}");
        assertEquals("", profile.dateTimeMillisExtra);
    }
}
