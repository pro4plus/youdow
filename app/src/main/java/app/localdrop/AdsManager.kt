package app.localdrop

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob interstitial shown once after the user taps download.
 *
 * The download always proceeds: if no ad is loaded, if showing fails, or
 * if an ad was shown recently, [showAfterDownload] runs [onDone]
 * immediately. At most one ad every 90 seconds, so repeated downloads
 * are not interrupted every time.
 */
object AdsManager {
    private const val AD_UNIT_ID = "ca-app-pub-8300421335705761/5882001077"
    private const val MIN_GAP_MS = 90_000L

    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var lastShownMs = 0L

    fun init(context: Context) {
        MobileAds.initialize(context) { preload(context.applicationContext) }
    }

    fun preload(context: Context) {
        if (loading || interstitial != null) return
        loading = true
        InterstitialAd.load(context.applicationContext, AD_UNIT_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    interstitial = null
                }
            })
    }

    /** Shows the interstitial when one is ready and the gap has passed,
     *  then runs [onDone] (start the download). Never blocks the download. */
    fun showAfterDownload(activity: Activity, onDone: () -> Unit) {
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null || now - lastShownMs < MIN_GAP_MS) {
            preload(activity)
            onDone()
            return
        }
        interstitial = null
        lastShownMs = now
        var done = false
        val finish = {
            if (!done) {
                done = true
                preload(activity)
                onDone()
            }
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
        }
        try {
            ad.show(activity)
        } catch (_: Exception) {
            finish()
        }
    }
}
