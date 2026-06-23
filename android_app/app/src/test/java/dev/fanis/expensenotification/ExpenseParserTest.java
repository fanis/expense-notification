package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static Candidate parseSms(String sender, String body) {
        return ExpenseParser.parse(SMS_PACKAGE, SMS_APP, "key", 0L, sender, body);
    }

    @Test
    public void bundledLocalBankDefaultsAreRegexConfigsNotLegacyHandlers() throws Exception {
        assertRegexOnlyBankConfig(new JSONObject(ExpenseParser.defaultInputJson("bank-of-cyprus.json")));
        assertRegexOnlyBankConfig(new JSONObject(ExpenseParser.defaultInputJson("eurobank.json")));
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

    private static String readAssetInput(String name) throws Exception {
        Path path = Path.of("src", "main", "assets", "inputs", name);
        if (!Files.exists(path)) {
            path = Path.of("app", "src", "main", "assets", "inputs", name);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void googleWalletBillBecomesMerchantAndCreditCard() {
        // Real Google Wallet notification: title is the merchant, body names the
        // underlying card. The raw "Revolut Visa ..NNNN" descriptor is not a kept
        // payment method, so it must fall back to the default card.
        Candidate c = ExpenseParser.parse(
                "com.google.android.apps.walletnfcrel", "Google Wallet", "k", 0L,
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
        Candidate c = ExpenseParser.parse(
                "com.revolut.revolut", "Revolut", "k", 0L,
                "SAMPLE UTILITY",
                "You spent €33.95\nEUR balance: €543.21");
        assertNotNull(c);
        assertEquals("EUR", c.currency);
        assertEquals("33.95", c.amount);
        assertEquals("SAMPLE UTILITY", c.merchant);
        assertEquals("Credit Card", c.suggestedPaymentMethod);
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

    private static Candidate parseRevolut(String title, String body) {
        return ExpenseParser.parse("com.revolut.business", "Business", "k", 0L, title, body);
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
    public void keepsCompletedRevolutPayment() {
        // The genuine completion must still pass after the new filters.
        Candidate c = parseRevolut(
                "SAMPLE UTILITY",
                "You spent €112.14\nEUR balance: €543.21");
        assertNotNull(c);
        assertEquals("112.14", c.amount);
        assertEquals("SAMPLE UTILITY", c.merchant);
    }
}
