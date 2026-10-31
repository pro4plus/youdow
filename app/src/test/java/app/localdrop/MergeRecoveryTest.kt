package app.localdrop

import org.junit.Assert.*
import org.junit.Test

class MergeRecoveryTest {
    @Test fun repairsReportedTimestampFailure() {
        assertTrue(MergeRecovery.needed("[matroska] Can't write packet with unknown timestamp", "312+bestaudio",false))
    }
    @Test fun doesNotRetryUnrelatedOrAudioFailures() {
        assertFalse(MergeRecovery.needed("HTTP 403", "312+bestaudio",false))
        assertFalse(MergeRecovery.needed("Conversion failed!", "312+bestaudio",false))
        assertFalse(MergeRecovery.needed("Timestamps are unset in a packet", "bestaudio",true))
        assertFalse(MergeRecovery.needed("Timestamps are unset in a packet", "18",false))
    }
}
