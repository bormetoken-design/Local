package com.local.deploy.supervisor

import com.local.deploy.model.RestartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {

    @Test
    fun testExponentialBackoff() {
        val watchdog = Watchdog(maxRestarts = 3, initialBackoffMs = 2000L)
        val now = System.currentTimeMillis()

        // 1st crash: 2s backoff
        val delay1 = watchdog.shouldRestart("proj-test", 1, RestartPolicy.ALWAYS, now)
        assertEquals(2000L, delay1)

        // 2nd crash: 4s backoff
        val delay2 = watchdog.shouldRestart("proj-test", 1, RestartPolicy.ALWAYS, now)
        assertEquals(4000L, delay2)

        // 3rd crash: 8s backoff
        val delay3 = watchdog.shouldRestart("proj-test", 1, RestartPolicy.ALWAYS, now)
        assertEquals(8000L, delay3)

        // 4th crash: Exceeds maxRestarts (3) -> returns -1
        val delay4 = watchdog.shouldRestart("proj-test", 1, RestartPolicy.ALWAYS, now)
        assertEquals(-1L, delay4)
    }

    @Test
    fun testRestartPolicyNever() {
        val watchdog = Watchdog()
        val delay = watchdog.shouldRestart("proj-never", 1, RestartPolicy.NEVER, System.currentTimeMillis())
        assertEquals(-1L, delay)
    }

    @Test
    fun testRestartPolicyOnFailureWithExitZero() {
        val watchdog = Watchdog()
        val delay = watchdog.shouldRestart("proj-on-fail", 0, RestartPolicy.ON_FAILURE, System.currentTimeMillis())
        assertEquals(-1L, delay)
    }
}
