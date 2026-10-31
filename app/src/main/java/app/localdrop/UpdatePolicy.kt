package app.localdrop

object UpdatePolicy {
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val RETRY = 15 * 60 * 1000L
    fun due(now: Long, lastSuccess: Long, lastAttempt: Long): Boolean {
        val successfulRecently = lastSuccess > 0 && now >= lastSuccess && now-lastSuccess < DAY
        val attemptedRecently = lastAttempt > 0 && now >= lastAttempt && now-lastAttempt < RETRY
        return !successfulRecently && !attemptedRecently
    }
}
