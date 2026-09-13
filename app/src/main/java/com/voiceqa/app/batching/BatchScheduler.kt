package com.voiceqa.app.batching

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FlushReason {
    MAXIMUM_CHARS,
    SILENCE_TIMEOUT,
    MAXIMUM_WAIT,
    USER_MANUAL,
    STOP_LISTENING
}

class BatchScheduler(
    private val scope: CoroutineScope,
    private val onTriggerFlush: suspend (FlushReason) -> Unit
) {
    private val mutex = Mutex()
    private var silenceJob: Job? = null
    private var maxWaitJob: Job? = null

    /**
     * Schedules or resets the silence timer, and starts the maximum wait timer if not already active.
     */
    suspend fun schedule(silenceDelayMs: Long, maximumDelayMs: Long) {
        mutex.withLock {
            // Cancel and reset silence job
            silenceJob?.cancel()
            silenceJob = scope.launch {
                delay(silenceDelayMs)
                onTriggerFlush(FlushReason.SILENCE_TIMEOUT)
            }

            // Start max wait timer if not already running
            if (maxWaitJob == null || maxWaitJob?.isActive == false) {
                maxWaitJob = scope.launch {
                    delay(maximumDelayMs)
                    onTriggerFlush(FlushReason.MAXIMUM_WAIT)
                }
            }
        }
    }

    /**
     * Resets both timers when a flush completes or buffer is emptied.
     */
    suspend fun cancel() {
        mutex.withLock {
            silenceJob?.cancel()
            silenceJob = null
            maxWaitJob?.cancel()
            maxWaitJob = null
        }
    }
}
