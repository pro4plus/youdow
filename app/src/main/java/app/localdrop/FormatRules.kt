package app.localdrop

import org.json.JSONObject

/**
 * Pure rules for deciding whether a yt-dlp format entry can actually be
 * downloaded. News sites and many others serve HLS/DASH only: the entry has
 * no direct `url`, only a `manifest_url` or a `fragments` list. The old
 * filter required a direct https `url` and dropped all of those, producing
 * "No downloadable formats" for pages yt-dlp could in fact download.
 */
object FormatRules {
    fun usable(f: JSONObject): Boolean {
        if (f.optBoolean("has_drm")) return false
        val url = f.optString("url")
        if (url.startsWith("http://") || url.startsWith("https://")) return true
        val manifest = f.optString("manifest_url")
        if (manifest.startsWith("http://") || manifest.startsWith("https://")) return true
        return (f.optJSONArray("fragments")?.length() ?: 0) > 0
    }
}
