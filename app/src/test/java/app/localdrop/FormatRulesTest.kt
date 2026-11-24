package app.localdrop

import org.junit.Assert.*
import org.junit.Test

class FormatRulesTest {
    @Test fun acceptsDirectHttpsUrl() {
        assertTrue(FormatRules.usable(FormatEntry("https://cdn.example.com/v.mp4", null, false, false)))
    }

    @Test fun acceptsManifestOnlyHls() {
        // News sites often serve HLS/DASH only: no direct url, just a manifest.
        assertTrue(FormatRules.usable(FormatEntry(null, "https://cdn.example.com/master.m3u8", false, false)))
    }

    @Test fun acceptsFragmentOnlyDash() {
        assertTrue(FormatRules.usable(FormatEntry(null, null, true, false)))
    }

    @Test fun rejectsDrmAndEmptyFormats() {
        assertFalse(FormatRules.usable(FormatEntry("https://cdn.example.com/v.mp4", null, false, true)))
        assertFalse(FormatRules.usable(FormatEntry(null, null, false, false)))
        assertFalse(FormatRules.usable(FormatEntry("blob:https://example.com/123", null, false, false)))
        assertFalse(FormatRules.usable(FormatEntry("", null, false, false)))
    }
}
