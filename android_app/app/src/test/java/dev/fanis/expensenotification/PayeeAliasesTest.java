package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PayeeAliasesTest {

    @Test
    public void stripsProcessorPrefixDigitsAndPunctuation() {
        assertEquals("SAMPLE COFFEE CITY CY",
                PayeeAliases.normalize("SQ *SAMPLE COFFEE#1234 CITY CY"));
    }

    @Test
    public void differentStoreNumbersShareOneKey() {
        assertEquals(
                PayeeAliases.normalize("SUMUP *COFFEE 12"),
                PayeeAliases.normalize("SUMUP *COFFEE 99"));
    }

    @Test
    public void cleanNameNormalizesToUppercase() {
        assertEquals("SAMPLE COFFEE", PayeeAliases.normalize("Sample Coffee"));
    }

    @Test
    public void keepsGreekLettersDropsDigits() {
        assertEquals("ΔΕΙΓΜΑ", PayeeAliases.normalize("ΔΕΙΓΜΑ 123"));
    }

    @Test
    public void nullMerchantIsEmptyKey() {
        assertEquals("", PayeeAliases.normalize(null));
    }
}
