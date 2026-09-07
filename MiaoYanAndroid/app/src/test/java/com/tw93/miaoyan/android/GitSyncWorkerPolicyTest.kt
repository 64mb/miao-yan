package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitSyncTrigger
import com.tw93.miaoyan.android.git.GitSyncWorkerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSyncWorkerPolicyTest {
    @Test
    fun periodicSyncObeysThePeriodicPreference() {
        assertTrue(GitSyncWorkerPolicy.shouldRun(GitSyncTrigger.Periodic, periodicEnabled = true))
        assertFalse(GitSyncWorkerPolicy.shouldRun(GitSyncTrigger.Periodic, periodicEnabled = false))
    }

    @Test
    fun appBackgroundSyncDoesNotDependOnPeriodicPreference() {
        assertTrue(GitSyncWorkerPolicy.shouldRun(GitSyncTrigger.AppBackground, periodicEnabled = true))
        assertTrue(GitSyncWorkerPolicy.shouldRun(GitSyncTrigger.AppBackground, periodicEnabled = false))
    }

    @Test
    fun appBackgroundUsesTheShortBestEffortBudget() {
        val background = GitSyncWorkerPolicy.budget(GitSyncTrigger.AppBackground)
        val periodic = GitSyncWorkerPolicy.budget(GitSyncTrigger.Periodic)

        assertEquals(8_000L, background.maximumRunMillis)
        assertEquals(6_000L, background.transportDeadlineMillis)
        assertTrue(background.maximumRunMillis < periodic.maximumRunMillis)
        assertTrue(background.transportDeadlineMillis < periodic.transportDeadlineMillis)
    }

    @Test
    fun missingLegacyTriggerFailsClosedAsPeriodic() {
        assertEquals(GitSyncTrigger.Periodic, GitSyncTrigger.from(null))
        assertEquals(GitSyncTrigger.Periodic, GitSyncTrigger.from("unknown"))
    }
}
