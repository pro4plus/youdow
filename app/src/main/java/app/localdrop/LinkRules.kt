package app.localdrop

import java.net.URI
import java.net.URLDecoder

object LinkRules {
    /** Host (or host suffix) -> Arabic platform name. yt-dlp itself supports
     *  1000+ sites, so an unknown https host is still attempted; this table
     *  only drives display names, platform-specific messages and short-link
     *  handling. Keep suffix matching strict: "youtube.com.attacker.test"
     *  must never match. */
    private val PLATFORMS = listOf(
        "youtube.com" to "يوتيوب", "youtu.be" to "يوتيوب",
        "youtube-nocookie.com" to "يوتيوب", "music.youtube.com" to "يوتيوب",
        "tiktok.com" to "تيك توك", "vm.tiktok.com" to "تيك توك",
        "vt.tiktok.com" to "تيك توك",
        "instagram.com" to "إنستغرام", "instagr.am" to "إنستغرام",
        "facebook.com" to "فيسبوك", "fb.watch" to "فيسبوك", "fb.com" to "فيسبوك",
        "x.com" to "إكس", "twitter.com" to "إكس", "t.co" to "إكس",
        "snapchat.com" to "سناب شات",
        "pinterest.com" to "بينترست", "pin.it" to "بينترست",
        "reddit.com" to "ريديت", "redd.it" to "ريديت",
        "twitch.tv" to "تويتش",
        "vimeo.com" to "فيميو",
        "dailymotion.com" to "ديلي موشن", "dai.ly" to "ديلي موشن",
        "soundcloud.com" to "ساوند كلاود",
        "threads.net" to "ثريدز", "threads.com" to "ثريدز",
        "t.me" to "تيليغرام", "telegram.me" to "تيليغرام",
        "linkedin.com" to "لينكدإن",
        "vk.com" to "في كيه", "vk.ru" to "في كيه", "ok.ru" to "أوكي",
        "bilibili.com" to "بيليبيلي", "b23.tv" to "بيليبيلي",
        "kwai.com" to "كواي", "likee.video" to "لايكي",
        "rumble.com" to "رامبل", "odysee.com" to "أوديسي",
        "streamable.com" to "ستريمابل", "mixcloud.com" to "ميكس كلاود"
    )

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "fbclid", "fb_action_ids", "fb_source", "igshid", "igsh",
        "si", "share_source", "spm", "scid", "mcid", "yclid", "gclid"
    )

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** Arabic display name for a URL: known platform, otherwise the bare host. */
    fun platformName(url: String): String {
        val host = hostOf(url) ?: return "فيديو"
        PLATFORMS.firstOrNull { (base, _) -> host == base || host.endsWith(".$base") }
            ?.let { return it.second }
        return host.removePrefix("www.")
    }

    fun youtube(url: String): Boolean {
        val h = hostOf(url) ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com") ||
            h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com")
    }

    /** True for a bare playlist link (YouTube list= / /playlist, SoundCloud set). */
    fun isPlaylist(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (youtube(url)) {
            if (uri.path?.contains("/playlist") == true) return true
            return uri.query.orEmpty().split("&").any { it.startsWith("list=") }
        }
        if (host == "soundcloud.com" || host.endsWith(".soundcloud.com"))
            return uri.path.orEmpty().contains("/sets/")
        return false
    }

    private val DIRECT_EXTS = setOf(
        "mp4", "webm", "mov", "mkv", "m4v", "avi",
        "mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus"
    )

    /** Direct media file link (CDN / direct mp4, mp3, ...): no extractor needed. */
    fun isDirectMedia(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return DIRECT_EXTS.any { path.endsWith(".$it") }
    }

    fun directTitle(url: String): String {
        val raw = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast("/").orEmpty()
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.substringBeforeLast(".").replace(Regex("[_+\\-]+"), " ").trim()
            .ifBlank { "ملف مباشر" }
    }

    /** Strip tracking parameters and fragments; keep the parameters that
     *  identify the video (v, list, t, ...). Never throws. */
    fun normalize(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val query = uri.query?.split("&")
            ?.filter { part -> part.substringBefore("=").lowercase() !in TRACKING_PARAMS }
            ?.joinToString("&").orEmpty()
        return runCatching {
            URI(
                uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(),
                uri.port, uri.path, query.ifEmpty { null }, null
            ).toASCIIString()
        }.getOrDefault(url.trim())
    }

    private val WEB_URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val BARE_DOMAINS = PLATFORMS.map { it.first }.distinct()
        .joinToString("|") { Regex.escape(it) }
    private val BARE_URL = Regex(
        "(?:^|[\\s\"'\\(\\[])(?:www\\.)?(($BARE_DOMAINS)/[^\\s<>\"']+)",
        RegexOption.IGNORE_CASE
    )

    private fun clean(raw: String): String? {
        val trimmed = raw.trimEnd('.', ',', ')', ']', ';', '!', '?')
        if (trimmed.isBlank()) return null
        return runCatching {
            val u = URI(trimmed)
            if (u.host != null && u.userInfo == null &&
                (u.scheme.equals("http", true) || u.scheme.equals("https", true))
            ) normalize(trimmed) else null
        }.getOrNull()
    }

    fun extract(text: String): String? = extractAll(text).firstOrNull()

    fun extractAll(text: String): List<String> {
        val found = linkedSetOf<String>()
        WEB_URL.findAll(text).mapNotNull { clean(it.value) }.forEach { found += it }
        if (found.isEmpty()) {
            BARE_URL.findAll(text).mapNotNull { clean("https://${it.groupValues[1]}") }
                .forEach { found += it }
        } else {
            // Bare known-domain links after the first full URL still count.
            BARE_URL.findAll(text).mapNotNull { clean("https://${it.groupValues[1]}") }
                .forEach { found += it }
        }
        return found.toList()
    }

    fun error(e: Throwable): String = classify(e, "يوتيوب")

    /** Same classification as [error], but names the actual platform. */
    fun errorFor(url: String, e: Throwable): String = classify(e, platformName(url))

    private fun classify(e: Throwable, platform: String): String {
        val raw = generateSequence(e) { it.cause }.take(5).joinToString("\n") { it.message.orEmpty() }.lowercase()
        // Verbose output always mentions QuickJS, EJS and other features, even when
        // those succeeded. Classify actual ERROR lines rather than debug banners.
        val errors = Regex("(?m)^error:.*$").findAll(raw).joinToString("\n") { it.value }
        val s = errors.ifBlank { raw }
        return when {
            "no space left on device" in raw || "enospc" in raw -> "المساحة غير كافية لإكمال الدمج أو الحفظ. حرّر مساحة ثم اضغط استئناف."
            "postprocessing" in s || "conversion failed" in s ->
                "فشلت معالجة الملف بعد التنزيل (دمج أو تحويل). ملفات المحاولة محفوظة داخل التطبيق. انسخ التفاصيل لتحديد السبب، أو اضغط استئناف لإعادة المحاولة."
            "drm" in s -> "الفيديو محمي بنظام DRM ولا يمكن تنزيله."
            "private" in s -> "هذا الفيديو خاص ولا يمكن تنزيله."
            "not a bot" in s || "confirm you’re not" in s || "confirm you're not" in s ||
                "429" in s || "rate limit" in s || "too many requests" in s ->
                "$platform يطلب التحقق أو يقيّد الطلبات من اتصالك. حاول لاحقًا؛ تحديث الأداة وحده قد لا يحل ذلك."
            listOf("login", "sign in", "log in", "cookies", "age-restricted").any { it in s } ->
                "الفيديو يحتاج تسجيل دخول أو تحققًا من العمر. التطبيق لا يدعم تسجيل الدخول."
            listOf("javascript", "ejs", "signature", "challenge", "quickjs").any { it in s } ->
                "تعذر تشغيل مكونات $platform. حدّث أداة التنزيل وتأكد من إمكانية الوصول إلى GitHub."
            "geo" in s || "not available in your country" in s || "blocked in your country" in s ->
                "الفيديو غير متاح في بلدك أو منطقتك."
            "live stream" in s || "is_live" in s || "live event" in s ->
                "البث المباشر غير مدعوم؛ جرّب بعد انتهاء البث."
            "403" in s -> "رفض الموقع الوصول إلى رابط التنزيل. افحص رابط الفيديو مجددًا لتحديث الجودات والروابط."
            "requested format" in s -> "الجودة المختارة لم تعد متاحة. افحص الفيديو مجددًا واختر جودة أخرى."
            "no downloadable formats" in s || "unable to extract" in s -> "لم يعثر المستخرج على جودات قابلة للتنزيل. حدّث الأداة ثم افحص الرابط مجددًا."
            "unsupported" in s -> "الرابط أو صيغة الفيديو غير مدعومة. جرّب تحديث أداة التنزيل."
            "not available" in s || "unavailable" in s || "404" in s || "removed" in s -> "الفيديو غير متاح أو تم حذفه."
            "permission" in s -> "تم رفض الإذن المطلوب. راجع التفاصيل وأذونات التطبيق."
            "timed out" in s || "resolve" in s || "network" in s -> "تعذر الاتصال. تحقق من الشبكة ثم أعد المحاولة."
            else -> "تعذرت معالجة الفيديو. حدّث أداة التنزيل ثم أعد المحاولة؛ يمكنك نسخ التفاصيل لمعرفة السبب."
        }
    }

    fun details(e: Throwable): String {
        val text = generateSequence(e) { it.cause }.take(5)
            .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            .replace(Regex("https?://[^\\s<>]+"), "[رابط محذوف]")
            .replace(Regex("(?i)(cookie|authorization|token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[محذوف]")
        // FFmpeg's useful error is near the END of stderr. Keep it, even for long logs.
        return if (text.length <= 7000) text else text.take(1000) + "\n[…]\n" + text.takeLast(6000)
    }

    fun report(e: Throwable): String = error(e) + "\n\nالتفاصيل الفنية:\n" + details(e)

    fun reportFor(url: String, e: Throwable): String =
        errorFor(url, e) + "\n\nالتفاصيل الفنية:\n" + details(e)
}
