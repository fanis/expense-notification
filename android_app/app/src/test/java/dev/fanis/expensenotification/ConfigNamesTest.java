package dev.fanis.expensenotification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConfigNamesTest {

    @Test
    public void cleansUnsafeFileNames() {
        assertEquals("my-bank.json", ConfigNames.cleanJsonName("my-bank"));
        assertEquals("my-bank.json", ConfigNames.cleanJsonName("  my-bank.json  "));
        assertEquals("a-b.json", ConfigNames.cleanJsonName("a/b"));
        assertEquals("config.json", ConfigNames.cleanJsonName(null));
        assertEquals("config.json", ConfigNames.cleanJsonName("   "));
    }

    @Test
    public void decodesJsonLevelUnicodeEscapes() {
        assertEquals("Η ΚΑΡΤΑ", ConfigNames.decodeUnicodeEscapes("\\u0397 \\u039A\\u0391\\u03A1\\u03A4\\u0391"));
        assertEquals("plain text", ConfigNames.decodeUnicodeEscapes("plain text"));
    }

    @Test
    public void leavesEscapedBackslashUnicodeSequencesAlone() {
        // \\u20AC inside a JSON string is a literal backslash + "u20AC" (e.g. a
        // regex-level unicode escape); decoding it would corrupt the pattern.
        assertEquals("\\\\u20AC", ConfigNames.decodeUnicodeEscapes("\\\\u20AC"));
        // Three backslashes: an escaped backslash followed by a real unicode escape.
        assertEquals("\\\\€", ConfigNames.decodeUnicodeEscapes("\\\\\\u20AC"));
    }

    @Test
    public void leavesMalformedEscapesAlone() {
        assertEquals("\\uZZZZ", ConfigNames.decodeUnicodeEscapes("\\uZZZZ"));
        assertEquals("\\u12", ConfigNames.decodeUnicodeEscapes("\\u12"));
        assertEquals("trailing\\", ConfigNames.decodeUnicodeEscapes("trailing\\"));
    }
}
