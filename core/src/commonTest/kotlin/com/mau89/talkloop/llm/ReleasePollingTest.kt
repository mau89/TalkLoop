package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReleasePollingTest {
    @Test fun temporaryFailuresAreRetriedUntilCompletedResultArrives() = runTest {
        var attempts = 0
        val errors = mutableListOf<String?>()
        val results = mutableListOf<String?>()
        monitorReleaseStatus(fetch = {
            attempts++
            if (attempts <= 2) throw IllegalStateException("Network unavailable")
            ReleaseJob("job", "HEAD~1", "1.1.1", 1, state = if (attempts == 3) "running" else "prepared")
        }, onResult = { results += it?.state }, onConnectionError = { errors += it.message })
        assertEquals(4, attempts)
        assertEquals(2, errors.size)
        assertEquals(listOf<String?>("running", "prepared"), results)
    }

    @Test fun leavingScreenCancelsPollingWithoutReportingNetworkError() = runTest {
        assertFailsWith<CancellationException> {
            monitorReleaseStatus(fetch = { throw CancellationException("screen left") },
                onResult = { fail("No result") }, onConnectionError = { fail("Cancellation is not a network error") })
        }
    }

    @Test fun emptyLatestResultDoesNotStartEndlessPolling() = runTest {
        var attempts = 0
        var received = false
        monitorReleaseStatus(fetch = { attempts++; null }, onResult = { assertNull(it); received = true }, onConnectionError = { fail(it.message) })
        assertEquals(1, attempts)
        assertTrue(received)
    }
}
