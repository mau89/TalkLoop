package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A lost status request does not cancel a server-owned release job. */
suspend fun monitorReleaseStatus(
    fetch: suspend () -> ReleaseJob?,
    onResult: (ReleaseJob?) -> Unit,
    onConnectionError: (Exception) -> Unit,
) {
    var pause = 3_000L
    while (true) {
        delay(pause)
        try {
            val result = fetch()
            onResult(result)
            if (result?.isRunning != true) return
            pause = 3_000L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onConnectionError(failure)
            pause = (pause * 2).coerceAtMost(15_000L)
        }
    }
}
