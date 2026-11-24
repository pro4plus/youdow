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
    @Test fun verboseFeaturesDoNotMisclassifyPostprocessing() {
        val e=Exception("[debug] JS runtimes: quickjs; ejs; private config\nERROR: Postprocessing: Conversion failed!")
        assertTrue(LinkRules.error(e).contains("بعد التنزيل"))
        assertFalse(LinkRules.error(e).contains("حدّث"))
    }
    @Test fun retainsTailOfLongFfmpegFailure() {
        val e=Exception("الصيغة: 137+140\n" + "[debug] configuration information\n".repeat(500) +
            "FFmpeg: Invalid data found when processing input\nERROR: Postprocessing: Conversion failed!")
        val details=LinkRules.details(e)
        assertTrue(details.contains("الصيغة: 137+140"))
        assertTrue(details.contains("Invalid data found"))
        assertTrue(details.length < 7100)
    }
    @Test fun verboseJavascriptBannerDoesNotHide403() {
        assertTrue(LinkRules.error(Exception("[debug] quickjs ejs\nERROR: HTTP Error 403: Forbidden")).contains("رفض الموقع"))
    }
    @Test fun namesAllMajorPlatforms() {
        assertEquals("تيك توك", LinkRules.platformName("https://www.tiktok.com/@x/video/123"))
        assertEquals("تيك توك", LinkRules.platformName("https://vm.tiktok.com/abc/"))
        assertEquals("إنستغرام", LinkRules.platformName("https://www.instagram.com/reel/abc/"))
        assertEquals("فيسبوك", LinkRules.platformName("https://fb.watch/abc/"))
        assertEquals("إكس", LinkRules.platformName("https://x.com/user/status/123"))
        assertEquals("إكس", LinkRules.platformName("https://twitter.com/user/status/123"))
        assertEquals("سناب شات", LinkRules.platformName("https://www.snapchat.com/spotlight/abc"))
        assertEquals("ريديت", LinkRules.platformName("https://old.reddit.com/r/x/comments/1/"))
        assertEquals("بينترست", LinkRules.platformName("https://pin.it/abc"))
        assertEquals("تويتش", LinkRules.platformName("https://clips.twitch.tv/abc"))
        assertEquals("فيميو", LinkRules.platformName("https://vimeo.com/123"))
        assertEquals("ديلي موشن", LinkRules.platformName("https://dai.ly/abc"))
        assertEquals("ساوند كلاود", LinkRules.platformName("https://soundcloud.com/user/track"))
        assertEquals("ثريدز", LinkRules.platformName("https://www.threads.net/@x/post/1"))
    }
    @Test fun platformSuffixMustBeExact() {
        assertEquals("tiktok.com.attacker.test", LinkRules.platformName("https://tiktok.com.attacker.test/video/1"))
        assertFalse(LinkRules.youtube("https://youtube.com.attacker.test/watch?v=abc"))
    }
    @Test fun stripsTrackingParametersButKeepsVideoId() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc&list=xyz",
            LinkRules.normalize("https://www.youtube.com/watch?v=abc&list=xyz&si=123&utm_source=share&fbclid=zzz")
        )
        assertEquals("https://youtu.be/abc", LinkRules.normalize("https://youtu.be/abc?si=123"))
    }
    @Test fun extractsBareDomainLinks() {
        assertEquals("https://tiktok.com/@x/video/123", LinkRules.extract("شوف هذا tiktok.com/@x/video/123"))
        assertEquals("https://youtu.be/abc", LinkRules.extract("youtu.be/abc"))
    }
    @Test fun extractsAllLinksForBatch() {
        val links = LinkRules.extractAll("https://youtu.be/a https://www.tiktok.com/@x/video/1 https://youtu.be/a")
        assertEquals(listOf("https://youtu.be/a", "https://www.tiktok.com/@x/video/1"), links)
    }
    @Test fun detectsPlaylistsAndDirectMedia() {
        assertTrue(LinkRules.isPlaylist("https://www.youtube.com/playlist?list=PL123"))
        assertTrue(LinkRules.isPlaylist("https://www.youtube.com/watch?v=abc&list=PL123"))
        assertFalse(LinkRules.isPlaylist("https://www.youtube.com/watch?v=abc"))
        assertTrue(LinkRules.isDirectMedia("https://cdn.example.com/clip.mp4?token=1"))
        assertTrue(LinkRules.isDirectMedia("https://example.com/song.mp3"))
        assertFalse(LinkRules.isDirectMedia("https://www.youtube.com/watch?v=abc"))
        assertEquals("my clip 2024", LinkRules.directTitle("https://cdn.example.com/my_clip-2024.mp4"))
    }
    @Test fun errorNamesActualPlatform() {
        assertTrue(LinkRules.errorFor("https://www.tiktok.com/@x/video/1", Exception("HTTP Error 429")).contains("تيك توك"))
        assertTrue(LinkRules.errorFor("https://vimeo.com/1", Exception("This video is not available in your country")).contains("بلدك"))
        assertTrue(LinkRules.error(Exception("ERROR: Unable to extract video")).contains("جودات قابلة للتنزيل"))
    }
    @Test fun detectsStaleExtractorFailures() {
        assertTrue(LinkRules.needsExtractorRefresh(Exception("No downloadable formats available; update extractor")))
        assertTrue(LinkRules.needsExtractorRefresh(IllegalStateException("yt-dlp: ERROR: No video formats found")))
        assertFalse(LinkRules.needsExtractorRefresh(Exception("Private video")))
        assertFalse(LinkRules.needsExtractorRefresh(Exception("HTTP Error 403")))
    }
}
