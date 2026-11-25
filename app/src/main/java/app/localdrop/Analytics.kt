package app.localdrop

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Firebase Analytics + Crashlytics.
 *
 * Privacy: events carry the platform (e.g. youtube), quality label and
 * format only. No link URLs, video titles, file names or user identity
 * are ever logged. Collection can be switched off in Settings; it is
 * on by default.
 */
object AppAnalytics {
    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        }
    }

    private fun log(context: Context, name: String, params: Bundle.() -> Unit = {}) {
        if (!context.app.store.analyticsEnabled) return
        runCatching {
            FirebaseAnalytics.getInstance(context).logEvent(name, Bundle().apply(params))
        }
    }

    /** Non-fatal error (e.g. a failed download) for Crashlytics. */
    fun recordError(context: Context, e: Throwable) {
        if (!context.app.store.analyticsEnabled) return
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
    }

    fun downloadStarted(context: Context, p: Preview, c: Choice) = log(context, "download_started") {
        putString("platform", LinkRules.platformName(p.url))
        putString("quality", c.label)
        putString("format", if (c.mp3) "mp3" else "video")
        putString("fallback", if (p.fallback) "yes" else "no")
    }

    fun downloadCompleted(context: Context, d: Download) = log(context, "download_completed") {
        putString("platform", LinkRules.platformName(d.url))
        putString("quality", d.qualityLabel)
        putString("format", if (d.mp3) "mp3" else "video")
        putString("fallback", if (d.fallback) "yes" else "no")
    }

    fun downloadFailed(context: Context, d: Download, e: Exception) = log(context, "download_failed") {
        putString("platform", LinkRules.platformName(d.url))
        putString("format", if (d.mp3) "mp3" else "video")
        putString("reason", e.javaClass.simpleName)
    }

    fun linkInspected(context: Context, url: String, count: Int) = log(context, "link_inspected") {
        putString("platform", LinkRules.platformName(url))
        putInt("count", count)
    }

    fun browserOpened(context: Context, startUrl: String) = log(context, "browser_opened") {
        putString("platform", LinkRules.platformName(startUrl))
    }

    fun extractorUpdated(context: Context, result: String) = log(context, "extractor_updated") {
        putString("outcome", when {
            result.contains("التفاصيل الفنية:") -> "failed"
            result.startsWith("تم تحديث") -> "updated"
            else -> "already_latest"
        })
    }

    fun fileShared(context: Context, d: Download) = log(context, "file_shared") {
        putString("platform", LinkRules.platformName(d.url))
        putString("format", if (d.mp3) "mp3" else "video")
    }
}
