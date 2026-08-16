package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class ExpenseParserTest {

    private static final String SMS_PACKAGE = "com.textra";
    private static final String SMS_APP = "Textra";

    // Real-Greek BOC account-debit samples. The gateway may instead send the
    // ambiguous capitals as Latin lookalikes; toLatinLookalike() reproduces that.
    private static final String FEE_SMS =
            "Ο ΛΟΓ/ΣΜΟΣ XXXX000000 (ΤΡΕΧΟΥΜΕΝΟΣ) ΧΡΕΩΘΗΚΕ ΜΕ ΤΟ ΠΟΣΟ ΤΩΝ EUR 7,50 "
                    + "ΣΤΙΣ 26/05/2026 20:17. ΠΕΡΙΓΡΑΦΗ: ΠΡΟΜΗΘΕΙΑ";
    private static final String TRANSFER_SMS =
            "Ο ΛΟΓ/ΣΜΟΣ XXXX000000 (ΤΡΕΧΟΥΜΕΝΟΣ) ΧΡΕΩΘΗΚΕ ΜΕ ΤΟ ΠΟΣΟ ΤΩΝ EUR 17,00 "
                    + "ΣΤΙΣ 28/05/2026 10:02. ΠΕΡΙΓΡΑΦΗ: ΜΕΤΑΦΟΡΑ (ΤΡ. ΚΥΠΡΟΥ)";

    /** Mimics an SMS gateway that swaps ambiguous Greek capitals for Latin twins. */
    private static String toLatinLookalike(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'Α': b.append('A'); break;
                case 'Β': b.append('B'); break;
                case 'Ε': b.append('E'); break;
                case 'Ζ': b.append('Z'); break;
                case 'Η': b.append('H'); break;
                case 'Ι': b.append('I'); break;
                case 'Κ': b.append('K'); break;
                case 'Μ': b.append('M'); break;
                case 'Ν': b.append('N'); break;
                case 'Ο': b.append('O'); break;
                case 'Ρ': b.append('P'); break;
                case 'Τ': b.append('T'); break;
                case 'Υ': b.append('Y'); break;
                case 'Χ': b.append('X'); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    // The bundled asset configs are the single source of truth; the JVM tests run
    // the exact JSON files that ship in the APK.
    private static Candidate parseAssets(String packageName, String appName, long postedAt, String title, String body) {
        try {
            return ExpenseParser.parseConfigured(
                    readAsset("global.json"), readAllAssetInputs(),
                    packageName, appName, "key", postedAt, title, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Candidate parseSms(String sender, String body) {
        return parseAssets(SMS_PACKAGE, SMS_APP, 0L, sender, body);
    }

    private static Candidate parseRevolut(String title, String body) {
        return parseAssets("com.revolut.business", "Business", 0L, title, body);
    }

    private static String formatDay(long postedAt) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(postedAt));
    }

    @Test
    public void bundledLocalBankConfigsAreRegexConfigsNotLegacyHandlers() throws Exception {
        assertRegexOnlyBankConfig(new JSONObject(readAssetInput("bank-of-cyprus.json")));
        assertRegexOnlyBankConfig(new JSONObject(readAssetInput("eurobank.json")));
    }

    @Test
    public void bundledInputConfigsValidateAndEmbeddedTestsPass() throws Exception {
        String[] inputs = {
                "alpha-bank.json",
                "bank-of-cyprus.json",
                "eurobank.json",
                "google-wallet.json",
                "paypal.json",
                "revolut.json"
        };
        for (String input : inputs) {
            String json = readAssetInput(input);
            ConfigValidator.Result validation = ConfigValidator.validateInput(input, json);
            assertTrue(input + "\n" + validation.summary(), validation.ok());
            ConfigSelfTest.Result tests = ConfigSelfTest.runInputConfig(input, json);
            assertTrue(input + " should define embedded tests", tests.count > 0);
            assertTrue(input + "\n" + tests.summary(), tests.passed());
        }
    }

    private static void assertRegexOnlyBankConfig(JSONObject config) throws Exception {
        JSONArray rules = config.getJSONArray("rules");
        boolean sawRegex = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            assertFalse(rule.toString(), rule.has("handler"));
            assertFalse(rule.toString(), "legacy".equals(rule.optString("type")));
            if ("regex".equals(rule.optString("type"))) {
                sawRegex = true;
            }
        }
        assertTrue("Expected at least one regex rule in " + config.optString("id"), sawRegex);
    }

    private static Path assetsDir() {
        Path path = Path.of("src", "main", "assets");
        if (!Files.exists(path)) {
            path = Path.of("app", "src", "main", "assets");
        }
        return path;
    }

    private static String readAsset(String name) throws IOException {
        return new String(Files.readAllBytes(assetsDir().resolve(name)), StandardCharsets.UTF_8);
    }

    private static String readAssetInput(String name) throws IOException {
        return readAsset("inputs/" + name);
    }

    private static LinkedHashMap<String, String> readAllAssetInputs() throws IOException {
        LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(assetsDir().resolve("inputs"))) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        }
        Collections.sort(files);
        for (Path file : files) {
            inputs.put(file.getFileName().toString(), new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        return inputs;
    }

    @Test
    public void googleWalletBillBecomesMerchantAndCreditCard() {
        // Real Google Wallet notification: title is the merchant, body names the
        // underlying card. The raw "Revolut Visa ..NNNN" descriptor is not a kept
        // payment method, so it must fall back to the default card.
        Candidate c = parseAssets(
                "com.google.android.apps.walletnfcrel", "Google Wallet", 0L,
                "SAMPLE BILLER",
                "€44.09 with Revolut Visa ••0000\nView your purchase\n100000000");
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("44.09", c.amount);
        assertEquals("SAMPLE BILLER", c.merchant);
        assertEquals("Credit Card", c.suggestedPaymentMethod);
    }

    @Test
    public void revolutSpendBecomesMerchantAndCreditCard() {
        // Real Revolut notification: title is the merchant, body is "You spent ...".
        Candidate c = parseAssets(
                "com.revolut.revolut", "Revolut", 0L,
                "SAMPLE UTILITY",
                "You spent €33.95\nEUR balance: €543.21");
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("33.95", c.amount);
        assertEquals("SAMPLE UTILITY", c.merchant);
        assertEquals("Credit Card", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesPayPalReceiptEmailFromGmail() {
        // Real Gmail notification for a PayPal payment receipt: title is the email
        // sender ("PayPal"), body is the subject plus snippet. The amount uses a
        // locale decimal comma ($17,67 USD).
        Candidate c = parseAssets(
                "com.google.android.gm", "Gmail", 0L,
                "PayPal",
                "Receipt for Your Payment to SAMPLE MERCHANT INC\n"
                        + "SAMPLE NAME, you successfully sent a payment.\n"
                        + "Hello, SAMPLE NAME\n"
                        + "You paid $17,67 USD to SAMPLE MERCHANT INC\n"
                        + "View or Manage Payment");
        assertNotNull(c);
        assertEquals("USD", c.currency);
        assertEquals("17.67", c.amount);
        assertEquals("SAMPLE MERCHANT INC", c.merchant);
        assertEquals("PayPal", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesPayPalReceiptEmailFromOtherEmailClients() {
        // The PayPal source watches the well-known Android email clients, not just
        // Gmail; each shows the sender ("PayPal") as the notification title.
        String[] clients = {
                "com.microsoft.office.outlook",
                "com.samsung.android.email.provider",
                "com.yahoo.mobile.client.android.mail",
                "ch.protonmail.android",
                "com.fsck.k9",
                "net.thunderbird.android"
        };
        for (String packageName : clients) {
            Candidate c = parseAssets(packageName, packageName, 0L,
                    "PayPal",
                    "Receipt for Your Payment to SAMPLE MERCHANT INC\n"
                            + "You paid €25,00 EUR to SAMPLE MERCHANT INC");
            assertNotNull(packageName, c);
            assertEquals("EUR", c.currency);
            assertEquals("25.00", c.amount);
            assertEquals("SAMPLE MERCHANT INC", c.merchant);
            assertEquals("PayPal", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void parsesPayPalYenReceiptWithNonLatinSymbol() {
        // Any Unicode currency symbol may precede the amount, and any ISO code may
        // follow it; grouped thousands without decimals must parse as whole yen.
        Candidate c = parseAssets(
                "com.google.android.gm", "Gmail", 0L,
                "PayPal",
                "Receipt for Your Payment to SAMPLE MERCHANT\nYou paid ¥1,500 JPY to SAMPLE MERCHANT");
        assertNotNull(c);
        assertEquals("JPY", c.currency);
        assertEquals("1500", c.amount);
        assertEquals("SAMPLE MERCHANT", c.merchant);
        assertEquals("PayPal", c.suggestedPaymentMethod);
    }

    @Test
    public void gmailNotificationFromOtherSenderIsNotCaptured() {
        // The PayPal source names both the Gmail package and the "PayPal" sender, so
        // a payment-shaped email from anyone else must not be claimed.
        Candidate c = parseAssets(
                "com.google.android.gm", "Gmail", 0L,
                "SAMPLE SENDER",
                "You paid $17,67 USD to SAMPLE MERCHANT INC");
        assertNull(c);
    }

    @Test
    public void nonPaymentPayPalEmailIsNotCaptured() {
        // A PayPal promo email with an amount in it is not a receipt; the source has
        // no amount fallback, so only receipt wording is captured.
        Candidate c = parseAssets(
                "com.google.android.gm", "Gmail", 0L,
                "PayPal",
                "SAMPLE NAME, save $5,00 USD on your next purchase");
        assertNull(c);
    }

    @Test
    public void parsesAlphaBankTransferSms() {
        Candidate c = parseSms("Alpha Bank",
                "YOU HAVE PLACED A TRANSFER TO SAMPLE****000 FOR 3.780,00EUR WITH REF REF000000");
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("3780.00", c.amount);
        assertEquals("SAMPLE****000", c.merchant);
        assertEquals("Transfers", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
        assertEquals("REF000000", c.note);
    }

    @Test
    public void parsesAlphaBankCardAuthorisedSms() {
        Candidate c = parseSms("Alpha Bank",
                "YOUR CARD SAMPLE VISA *0000 WAS AUTHORISED FOR AN INDICATIVE AMOUNT 36,79 EUR ON 03/12/2023 13:01 AT SAMPLE MERCHANT - COUNTRY");
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("36.79", c.amount);
        assertEquals("SAMPLE MERCHANT - COUNTRY", c.merchant);
        assertEquals("Credit Card", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocFeeDebit() {
        Candidate c = parseSms("BOC Message", FEE_SMS);
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("7.50", c.amount);
        assertEquals("ΠΡΟΜΗΘΕΙΑ", c.merchant);
        assertEquals("Bank fees", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocTransferDebit() {
        Candidate c = parseSms("BOC Message", TRANSFER_SMS);
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("17.00", c.amount);
        assertEquals("ΜΕΤΑΦΟΡΑ (ΤΡ. ΚΥΠΡΟΥ)", c.merchant);
        assertEquals("Transfers", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    /** The Latin-lookalike encoding must yield the same structured fields. */
    @Test
    public void latinLookalikeEncodingMatchesGreek() {
        Candidate greek = parseSms("BOC Message", FEE_SMS);
        Candidate latin = parseSms("BOC Message", toLatinLookalike(FEE_SMS));
        assertNotNull(latin);
        assertEquals(greek.currency, latin.currency);
        assertEquals(greek.amount, latin.amount);
        assertEquals(greek.suggestedCategory, latin.suggestedCategory);
        assertEquals(greek.suggestedPaymentMethod, latin.suggestedPaymentMethod);
    }

    /** Exact gateway text from a real 17/06/2026 BOC transfer that failed to match. */
    @Test
    public void parsesRealWorldBocTransferGatewayText() {
        String sms = "O ΛOΓ/ΣMOΣ XXXX000000 (TPEXOYMENOΣ) "
                + "XPEΩΘHKE ME TO ΠOΣO TΩN EUR 17,00 "
                + "ΣTIΣ 17/06/2026 10:04. ΠEPIΓPAΦH: "
                + "METAΦOPA (TP. KYΠPOY)";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("17.00", c.amount);
        assertEquals("Transfers", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocProvidedCardGatewayText() {
        String sms = "H KAPTA ΣAΣ MCARD6004 EXEI XPHΣIMOΠOIHΘEI ΣTO SAMPLE MERCHANT CY "
                + "ΣTIΣ 12/03/2026, 14:06 ΓIA TO ENΔEIKTIKO ΠOΣO €44,09.";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("44.09", c.amount);
        assertEquals("SAMPLE MERCHANT CY", c.merchant);
        assertEquals("Credit Card", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocProvidedPaymentOrderDebitGatewayText() {
        String sms = "O ΛOΓ/ΣMOΣ XXXX000000 (TPEXOYMENOΣ) XPEΩΘHKE ME TO ΠOΣO TΩN EUR 253,94 "
                + "ΣTIΣ 10/03/2026 01:50. ΠEPIΓPAΦH: ENTOΛH ΠΛHPΩMHΣ DEBIT-SEPA DD SAMPLE BILL 00000000     REF REF0000000000000";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("253.94", c.amount);
        assertEquals("ENTOΛH ΠΛHPΩMHΣ DEBIT-SEPA DD SAMPLE BILL 00000000 REF REF0000000000000", c.merchant);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocProvidedGreekIncomingCreditGatewayText() {
        String sms = "O ΛOΓ/ΣMOΣ XXXX000000 (TPEXOYMENOΣ) ΠIΣTΩΘHKE ME TO ΠOΣO TΩN EUR 1.234,56 "
                + "ΣTIΣ 30/01/2024 16:39. ΠEPIΓPAΦH: EIΣEPXOMENO EMBAΣMA INWARD CY000000000000 BY SAMPLE SENDER>FT00000000>SAMPLE CREDIT DESCRIPTION";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertTrue(c.isIncome());
        assertEquals("EUR", c.currency);
        assertEquals("1234.56", c.amount);
        assertEquals("SAMPLE SENDER", c.merchant);
        assertEquals("SAMPLE CREDIT DESCRIPTION", c.note);
        assertEquals("Income", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesBocCardPurchaseInBothEncodings() {
        String card = "Η ΚΑΡΤΑ ΣΑΣ VISA*1234 ΕΧΕΙ ΧΡΗΣΙΜΟΠΟΙΗΘΕΙ ΣΤΟ SAMPLE SUPERMARKET "
                + "ΣΤΙΣ 26/05/2026, 20:17 ΓΙΑ ΤΟ ΕΝΔΕΙΚΤΙΚΟ ΠΟΣΟ €15,30.";
        for (String body : new String[] {card, toLatinLookalike(card)}) {
            Candidate c = parseSms("BOC Message", body);
            assertNotNull(c);
            assertEquals("EUR", c.currency);
            assertEquals("15.30", c.amount);
            assertEquals("SAMPLE SUPERMARKET", c.merchant);
            assertEquals("Groceries", c.suggestedCategory);
            assertEquals("Credit Card", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void parsesEurobankInBothEncodings() {
        String sms = "Η ΚΑΡΤΑ *0000 ΕΓΚΡΙΘΗΚΕ ΓΙΑ SAMPLE MERCHANT €35,16 @18:50";
        for (String body : new String[] {sms, toLatinLookalike(sms)}) {
            Candidate c = parseSms("Eurobank", body);
            assertNotNull(c);
            assertEquals("EUR", c.currency);
            assertEquals("35.16", c.amount);
            assertEquals("SAMPLE MERCHANT", c.merchant);
            assertEquals("Credit Card", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void datesExpenseToTransactionDateInSmsBody() {
        // The SMS arrived (notification posted) far later than the transaction; the
        // expense must be dated to the date written in the SMS, not the arrival time.
        long arrivedNov2023 = 1_700_000_000_000L;
        Candidate c = parseAssets(SMS_PACKAGE, SMS_APP, arrivedNov2023, "BOC Message",
                "Η ΚΑΡΤΑ ΣΑΣ VISA*1234 ΕΧΕΙ ΧΡΗΣΙΜΟΠΟΙΗΘΕΙ ΣΤΟ SAMPLE STORE "
                        + "ΣΤΙΣ 26/05/2026, 20:17 ΓΙΑ ΤΟ ΕΝΔΕΙΚΤΙΚΟ ΠΟΣΟ €15,30.");
        assertNotNull(c);
        assertEquals("2026-05-26", formatDay(c.postedAt));
    }

    @Test
    public void datesIncomingCreditToTwoDigitYearDate() {
        long arrived = 1_700_000_000_000L;
        Candidate c = parseAssets(SMS_PACKAGE, SMS_APP, arrived, "BOC Message",
                "The a/c XXXX000000 (CURRENT) was credited with the amount of "
                        + "EUR 300,00 on 23/05/26 12:55 From: SAMPLE PAYER Details: SAMPLE NOTE");
        assertNotNull(c);
        assertEquals("2026-05-23", formatDay(c.postedAt));
    }

    @Test
    public void keepsNotificationPostTimeWhenSmsHasNoDate() {
        // Revolut spend notifications carry no transaction date, so the expense keeps
        // the notification's post time.
        long arrived = 1_700_000_000_000L;
        Candidate c = parseAssets("com.revolut.revolut", "Revolut", arrived,
                "SAMPLE UTILITY", "You spent €33.95\nEUR balance: €543.21");
        assertNotNull(c);
        assertEquals(arrived, c.postedAt);
    }

    @Test
    public void parsesEurobankCardApprovedWithThousandsSeparator() {
        // Real notification: €3.000,00 is three thousand euros (European grouping),
        // not 3.00. The card-approved amount group must accept grouped thousands.
        String sms = "Η ΚΑΡΤΑ *0808 ΕΓΚΡΙΘΗΚΕ ΓΙΑ SAMPLE MERCHANT €3.000,00 @12:33";
        for (String body : new String[] {sms, toLatinLookalike(sms)}) {
            Candidate c = parseSms("EurobankCY", body);
            assertNotNull(c);
            assertEquals("EUR", c.currency);
            assertEquals("3000.00", c.amount);
            assertEquals("SAMPLE MERCHANT", c.merchant);
            assertEquals("Credit Card", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void parsesBocCardPurchaseWithThousandsSeparator() {
        String card = "Η ΚΑΡΤΑ ΣΑΣ VISA*1234 ΕΧΕΙ ΧΡΗΣΙΜΟΠΟΙΗΘΕΙ ΣΤΟ SAMPLE STORE "
                + "ΣΤΙΣ 26/05/2026, 20:17 ΓΙΑ ΤΟ ΕΝΔΕΙΚΤΙΚΟ ΠΟΣΟ €1.250,00.";
        for (String body : new String[] {card, toLatinLookalike(card)}) {
            Candidate c = parseSms("BOC Message", body);
            assertNotNull(c);
            assertEquals("EUR", c.currency);
            assertEquals("1250.00", c.amount);
            assertEquals("SAMPLE STORE", c.merchant);
            assertEquals("Credit Card", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void genericBocSmsLeavesPayeeEmptyAndBillsElectronicTransfer() {
        // A BOC SMS that matches no specific pattern (e.g. a balance alert) still gets
        // sensible defaults: empty payee, SMS text as note, Electronic Transfer method.
        String sms = "Your account XXXX000000 available balance is EUR 500,00 as at 29/05/26";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertEquals("EXPENSE", c.transactionType);
        assertEquals("EUR", c.currency);
        assertEquals("500.00", c.amount);
        assertEquals("", c.merchant);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
        assertEquals(sms, c.note);
    }

    @Test
    public void parsesBocIncomingCredit() {
        String sms = "The a/c XXXX000000 (CURRENT) was credited with the amount of "
                + "EUR 300,00 on 23/05/26 12:55 From: SAMPLE PAYER Details: SAMPLE NOTE";
        Candidate c = parseSms("BOC Message", sms);
        assertNotNull(c);
        assertTrue(c.isIncome());
        assertEquals("INCOME", c.transactionType);
        assertEquals("EUR", c.currency);
        assertEquals("300.00", c.amount);
        assertEquals("SAMPLE PAYER", c.merchant);
        assertEquals("SAMPLE NOTE", c.note);
        assertEquals("Income", c.suggestedCategory);
        assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
    }

    @Test
    public void parsesEurobankCyIncomingCreditWithThousandsSeparator() {
        // Amount before currency, European grouping (1.234,56 -> 1234.56), lots of padding.
        String sms = "Ο ΛΟΓΑΡΙΑΣΜΟΣ ΣΑΣ 000-00-******-00 ΕΧΕΙ ΠΙΣΤΩΘΕΙ ΜΕ ΤΟ ΠΟΣΟ ΤΩΝ                "
                + "1.234,56 EUR ΣΤΙΣ 27/11/2025,08:44";
        for (String body : new String[] {sms, toLatinLookalike(sms)}) {
            Candidate c = parseSms("EurobankCY", body);
            assertNotNull(c);
            assertTrue(c.isIncome());
            assertEquals("EUR", c.currency);
            assertEquals("1234.56", c.amount);
            assertEquals("Income", c.suggestedCategory);
            assertEquals("Electronic Transfer", c.suggestedPaymentMethod);
        }
    }

    @Test
    public void rejectsPendingApprovalPrompt() {
        // The 3DS approval request that precedes the real "successful" notification.
        Candidate c = parseRevolut(
                "Verify a payment",
                "A card payment of €112.14 to SAMPLE UTILITY is waiting for your approval. Tap to start");
        assertNull(c);
    }

    @Test
    public void rejectsDecline() {
        Candidate c = parseRevolut("Card payment declined", "Insufficient balance. Tap for details");
        assertNull(c);
    }

    @Test
    public void rejectsCardVerification() {
        Candidate c = parseRevolut("Card verification", "JCC Smart verified your card. You haven't been charged");
        assertNull(c);
    }

    @Test
    public void rejectsZeroAmount() {
        // A zero charge (verification/registration) is not an expense, independent of wording.
        Candidate c = parseRevolut("Some Shop", "You spent €0\nEUR balance: €10.00");
        assertNull(c);
    }

    @Test
    public void dropZeroAmountFalseKeepsZeroCharges() throws Exception {
        // The global dropZeroAmount switch must actually control the zero filter.
        Candidate c = ExpenseParser.parseConfigured(
                "{\"dropZeroAmount\":false}", readAllAssetInputs(),
                "com.revolut.revolut", "Revolut", "k", 0L,
                "Some Shop", "You spent €0\nEUR balance: €10.00");
        assertNotNull(c);
        assertEquals("0", c.amount);
    }

    @Test
    public void keepsCompletedRevolutPayment() {
        // The genuine completion must still pass after the new filters.
        Candidate c = parseRevolut(
                "SAMPLE UTILITY",
                "You spent €112.14\nEUR balance: €543.21");
        assertNotNull(c);
        assertEquals("112.14", c.amount);
        assertEquals("SAMPLE UTILITY", c.merchant);
    }

    // ---- Amount normalization: European/English grouping and decimals. ----

    @Test
    public void normalizesGroupedThousandsWithoutDecimals() {
        assertEquals("1234", ExpenseParser.normalizeAmount("1.234"));
        assertEquals("1234", ExpenseParser.normalizeAmount("1,234"));
        assertEquals("1234567", ExpenseParser.normalizeAmount("1.234.567"));
    }

    @Test
    public void normalizesDecimalsAndMixedSeparators() {
        assertEquals("1234.56", ExpenseParser.normalizeAmount("1.234,56"));
        assertEquals("1234.56", ExpenseParser.normalizeAmount("1,234.56"));
        assertEquals("300.00", ExpenseParser.normalizeAmount("300,00"));
        assertEquals("12.34", ExpenseParser.normalizeAmount("12.34"));
        assertEquals("3.5", ExpenseParser.normalizeAmount("3,5"));
    }

    @Test
    public void groupedThousandsWithoutDecimalsParseAsWholeAmount() {
        // "€1.234" is one thousand two hundred thirty four euros, not 1.23.
        Candidate c = parseAssets("com.revolut.revolut", "Revolut", 0L,
                "SAMPLE STORE", "You spent €1.234\nEUR balance: €5.678,90");
        assertNotNull(c);
        assertEquals("1234", c.amount);
        assertEquals("EUR", c.currency);
    }
}
