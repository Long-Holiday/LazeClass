package com.voiceqa.app.batching

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AnalysisQueue(
    private val scope: CoroutineScope,
    private val consumer: suspend (AnalysisBatch) -> Unit
) {
    private val channel = Channel<AnalysisBatch>(capacity = Channel.UNLIMITED)
    private var workerJob: Job? = null

    fun start() {
        if (workerJob != null && workerJob?.isActive == true) return
        workerJob = scope.launch {
            for (batch in channel) {
                consumer(batch)
            }
        }
    }

    suspend fun enqueue(batch: AnalysisBatch) {
        channel.send(batch)
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
    }
}
