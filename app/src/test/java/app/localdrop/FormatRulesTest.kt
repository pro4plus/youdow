package app.localdrop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormatRulesTest {
    private fun format(json: String) = JSONObject(json)

    @Test fun acceptsDirectHttpsUrl() {
        assertTrue(FormatRules.usable(format("""{"format_id":"18","url":"https://cdn.example.com/v.mp4","ext":"mp4"}""")))
    }

    @Test fun acceptsManifestOnlyHls() {
        // News sites often serve HLS/DASH only: no direct url, just a manifest.
        assertTrue(FormatRules.usable(format("""{"format_id":"hls-720","manifest_url":"https://cdn.example.com/master.m3u8","protocol":"m3u8"}""")))
    }

    @Test fun acceptsFragmentOnlyDash() {
        assertTrue(FormatRules.usable(format("""{"format_id":"dash-1080","fragments":[{"url":"https://cdn.example.com/seg1.m4s"}]}""")))
    }

    @Test fun rejectsDrmAndEmptyFormats() {
        assertFalse(FormatRules.usable(format("""{"format_id":"x","url":"https://cdn.example.com/v.mp4","has_drm":true}""")))
        assertFalse(FormatRules.usable(format("""{"format_id":"x","ext":"mp4"}""")))
        assertFalse(FormatRules.usable(format("""{"format_id":"x","url":"blob:https://example.com/123"}""")))
        assertFalse(FormatRules.usable(format("""{"format_id":"x","url":""}""")))
    }

    @Test fun emptyFragmentsDoNotCount() {
        assertFalse(FormatRules.usable(format("""{"format_id":"x","fragments":[]}""")))
        assertTrue(JSONArray().length() == 0)
    }
}
