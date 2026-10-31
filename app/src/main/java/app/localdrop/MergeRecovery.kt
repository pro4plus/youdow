package app.localdrop

object MergeRecovery {
    fun needed(error: String, selector: String, mp3: Boolean): Boolean {
        val text=error.lowercase()
        return !mp3 && '+' in selector &&
            ("can't write packet with unknown timestamp" in text || "timestamps are unset in a packet" in text)
    }
    // Merger's output arguments come after its stream-copy arguments, so this
    // overrides video copy only. Preserve audio with stream copy, dimensions and
    // decoded timing; do not invent a fixed frame rate or flatten variable timing.
    const val OUTPUT_ARGS = "Merger+ffmpeg_o:-c:v libx264 -preset veryfast -crf 18 -c:a copy"
}
