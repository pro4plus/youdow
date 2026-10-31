package app.localdrop

import org.junit.Assert.*
import org.junit.Test

class LinkRulesTest {
    @Test fun extractsLinkFromShareText() {
        assertEquals("https://youtu.be/abc",LinkRules.extract("Watch this: https://youtu.be/abc."))
    }
    @Test fun rejectsNonWebInput() {
        assertNull(LinkRules.extract("file:///etc/passwd"))
        assertNull(LinkRules.extract("https://user:password@example.com/video"))
    }
    @Test fun restrictsYouTubeFallbackToRealHosts() {
        assertTrue(LinkRules.youtube("https://m.youtube.com/watch?v=abc"))
        assertFalse(LinkRules.youtube("https://youtube.com.attacker.test/watch?v=abc"))
        assertFalse(LinkRules.youtube("https://attacker.test/youtube.com"))
    }
    @Test fun distinguishesAccessFailures() {
        assertTrue(LinkRules.error(Exception("Private video")).contains("private"))
        assertTrue(LinkRules.error(Exception("Sign in to confirm your age")).contains("login"))
        assertTrue(LinkRules.error(Exception("DRM protected")).contains("DRM"))
    }
}
