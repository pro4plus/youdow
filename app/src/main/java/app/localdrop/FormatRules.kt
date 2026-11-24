package app.localdrop

/** The only facts a yt-dlp format entry needs for the download decision.
 *  Kept separate from org.json so the rule stays pure-Kotlin and unit-testable:
 *  the android.jar org.json classes are stubs in JVM unit tests. */
data class FormatEntry(
    val url: String?,
    val manifestUrl: String?,
    val hasFragments: Boolean,
    val hasDrm: Boolean,
)

/**
 * Decides whether a yt-dlp format entry can actually be downloaded. News
 * sites and many others serve HLS/DASH only: the entry has no direct `url`,
 * only a `manifest_url` or a `fragments` list. The old filter required a
 * direct https `url` and dropped all of those, producing "No downloadable
 * formats" for pages yt-dlp could in fact download.
 */
object FormatRules {
    fun usable(f: FormatEntry): Boolean {
        if (f.hasDrm) return false
        val url = f.url.orEmpty()
        if (url.startsWith("http://") || url.startsWith("https://")) return true
        val manifest = f.manifestUrl.orEmpty()
        if (manifest.startsWith("http://") || manifest.startsWith("https://")) return true
        return f.hasFragments
    }
}
