package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class ConfigRuntimeRobolectricTest {
    @Test
    public void contextParserLoadsBundledAssetConfigs() {
        Context context = RuntimeEnvironment.getApplication();
        Candidate candidate = ExpenseParser.parse(
                context,
                "com.google.android.apps.walletnfcrel",
                "Google Wallet",
                "k",
                0L,
                "SAMPLE BILLER",
                "€44.09 with Revolut Visa ••0000\nView your purchase");

        assertNotNull(candidate);
        assertEquals("44.09", candidate.amount);
        assertEquals("SAMPLE BILLER", candidate.merchant);
        assertEquals("Credit Card", candidate.suggestedPaymentMethod);
    }

    @Test
    public void userInputOverrideIsLoadedAfterRevisionBump() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "inputs");
        assertTrue(dir.mkdirs() || dir.exists());
        Files.write(new File(dir, "test-bank.json").toPath(), userInputJson().getBytes(StandardCharsets.UTF_8));
        ConfigRevision.bump(context);

        ExpenseParser.ParseDiagnostics diagnostics = ExpenseParser.debug(
                context,
                "dev.test.bank",
                "Test Bank",
                "k",
                0L,
                "Test Bank",
                "PAID EUR 21.50 AT LOCAL CAFE");
        Candidate candidate = diagnostics.candidate;

        assertNotNull(diagnostics.steps.toString(), candidate);
        assertEquals("21.50", candidate.amount);
        assertEquals("LOCAL CAFE", candidate.merchant);
        assertEquals("Test Bank", candidate.suggestedPaymentMethod);
    }

    @Test
    public void confirmedSmsAppCanMatchSenderBasedBankConfig() {
        Context context = RuntimeEnvironment.getApplication();
        SmsApps.addPackage(context, "dev.sms");

        Candidate candidate = ExpenseParser.parse(
                context,
                "dev.sms",
                "Custom SMS",
                "k",
                0L,
                "BOC Message",
                "The a/c XXXX000000 (CURRENT) was credited with the amount of EUR 300,00 "
                        + "on 23/05/26 12:55 From: SAMPLE PAYER Details: SAMPLE NOTE");

        assertNotNull(candidate);
        assertEquals("300.00", candidate.amount);
        assertEquals("Income", candidate.suggestedCategory);
    }

    @Test
    public void activeOutputProfileLoadsUserProfileAfterRevisionBump() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "outputs");
        assertTrue(dir.mkdirs() || dir.exists());
        Files.write(new File(dir, "test-output.json").toPath(), userOutputJson().getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("active_output", "test-output")
                .apply();
        ConfigRevision.bump(context);

        OutputProfile profile = OutputProfile.active(context);

        assertEquals("test-output", profile.id);
        assertEquals("dev.output", profile.packageName);
        assertEquals("amount_value", profile.amountExtra());
    }

    private static String userInputJson() {
        return "{"
                + "\"id\":\"test-bank\","
                + "\"displayName\":\"Test Bank\","
                + "\"enabled\":true,"
                + "\"priority\":1,"
                + "\"match\":{\"packages\":[\"dev.test.bank\"]},"
                + "\"rules\":[{"
                + "\"name\":\"paid-at\","
                + "\"type\":\"regex\","
                + "\"pattern\":\"PAID\\\\s+(?<currency>EUR)\\\\s+(?<amount>[0-9]+(?:[.,][0-9]{2})?)\\\\s+AT\\\\s+(?<merchant>.+)\","
                + "\"flags\":[\"caseInsensitive\"],"
                + "\"output\":{\"paymentMethod\":\"Test Bank\",\"category\":\"@keyword\"}"
                + "}]"
                + "}";
    }

    private static String userOutputJson() {
        return "{"
                + "\"id\":\"test-output\","
                + "\"displayName\":\"Test Output\","
                + "\"package\":\"dev.output\","
                + "\"activity\":\"dev.output.Add\","
                + "\"fieldMap\":{\"amount\":\"amount_value\",\"payee\":\"payee_value\"},"
                + "\"dateFormat\":\"yyyy-MM-dd\""
                + "}";
    }
}
