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
        assertTrue(LinkRules.error(Exception("Private video")).contains("خاص"))
        assertTrue(LinkRules.error(Exception("Sign in to confirm your age")).contains("تسجيل دخول"))
        assertTrue(LinkRules.error(Exception("DRM protected")).contains("DRM"))
    }
    @Test fun classifiesYoutubeFailures() {
        assertTrue(LinkRules.error(Exception("Sign in to confirm you're not a bot")).contains("يقيّد"))
        assertTrue(LinkRules.error(Exception("signature challenge failed")).contains("مكونات يوتيوب"))
        assertTrue(LinkRules.error(Exception("HTTP Error 403")).contains("رفض الموقع"))
    }
    @Test fun detailsKeepCauseWithoutSignedUrls() {
        val e=IllegalStateException("NewPipe failed",Exception("yt-dlp HTTP 403 https://video.test/file?token=secret"))
        val report=LinkRules.report(e)
        assertTrue(report.contains("NewPipe failed"))
        assertTrue(report.contains("yt-dlp HTTP 403"))
        assertFalse(report.contains("video.test"))
        assertFalse(report.contains("secret"))
    }
}
