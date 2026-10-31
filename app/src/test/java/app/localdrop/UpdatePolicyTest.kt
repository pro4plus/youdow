package app.localdrop

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val now=1_800_000_000_000L
    @Test fun freshInstallChecksAutomatically() {
        assertTrue(UpdatePolicy.due(now,0,0))
    }
    @Test fun successPreventsRepeatChecksForOneDay() {
        assertFalse(UpdatePolicy.due(now,now-60_000,now-60_000))
        assertTrue(UpdatePolicy.due(now,now-86_400_000,now-86_400_000))
    }
    @Test fun failureBacksOffButDoesNotWaitWholeDay() {
        assertFalse(UpdatePolicy.due(now,0,now-60_000))
        assertTrue(UpdatePolicy.due(now,0,now-900_000))
    }
    @Test fun clockRollbackDoesNotSuppressUpdatesIndefinitely() {
        assertTrue(UpdatePolicy.due(now,now+1,now+1))
    }
}
