package app.localdrop

import java.net.URI

object LinkRules {
    fun extract(text: String): String? = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';', '!') }
        .firstOrNull { runCatching { val u=URI(it); u.host != null && u.userInfo == null }.getOrDefault(false) }
    fun youtube(url: String): Boolean {
        val h = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }
    fun error(e: Throwable): String {
        val s = e.message.orEmpty().lowercase()
        return when {
            "drm" in s -> "DRM-protected content is not supported."
            "private" in s -> "This video is private and cannot be downloaded."
            listOf("login", "sign in", "log in", "cookies", "age-restricted").any { it in s } ->
                "This video requires login or age verification. Account access is not supported."
            "unsupported" in s -> "This link or media format is not supported. Try updating the extractor."
            "not available" in s || "unavailable" in s || "404" in s -> "This video is unavailable or has been removed."
            "space" in s || "enospc" in s -> "Not enough free space. Free storage and retry."
            "permission" in s -> "Storage access was denied. Check permissions and retry."
            "timed out" in s || "resolve" in s || "network" in s -> "Network connection failed. Check your connection and retry."
            else -> "The source could not be processed. Update the extractor, then retry. The site may have changed or blocked access."
        }
    }
}
