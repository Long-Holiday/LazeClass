package com.voiceqa.app.batching

import java.util.UUID

class TranscriptBuffer {
    private val lock = Any()
    private val pending = mutableListOf<TranscriptSegment>()
    private val inFlight = mutableMapOf<String, List<TranscriptSegment>>()
    private val committed = ArrayDeque<TranscriptSegment>()

    fun append(segment: TranscriptSegment) {
        synchronized(lock) {
            pending.add(segment)
        }
    }

    fun pendingChars(): Int {
        synchronized(lock) {
            return pending.sumOf { it.text.length }
        }
    }

    fun pendingCount(): Int {
        synchronized(lock) {
            return pending.size
        }
    }

    /**
     * Creates an atomic snapshot batch from pending segments.
     * Moves pending segments into inFlight under the generated batch ID.
     */
    fun snapshot(
        policy: BatchPolicy,
        previouslyAnswered: List<String> = emptyList(),
        force: Boolean = false
    ): AnalysisBatch? {
        synchronized(lock) {
            if (pending.isEmpty()) return null
            val currentPendingChars = pendingChars()
            if (!force && currentPendingChars < policy.minimumChars) {
                return null
            }

            val batchId = UUID.randomUUID().toString()
            val batchSegments = pending.toList()
            pending.clear()
            inFlight[batchId] = batchSegments

            val sessionId = batchSegments.first().sessionId
            val segmentIds = batchSegments.map { it.id }

            // Build context from committed segments (up to policy.contextChars)
            val context = buildContext(policy.contextChars)

            // Build newText formatted with IDs: e.g. "[99] 那故宫几点关门\n[100] 门票多少钱"
            val newText = batchSegments.joinToString("\n") { "[${it.id}] ${it.text}" }

            return AnalysisBatch(
                id = batchId,
                sessionId = sessionId,
                segmentIds = segmentIds,
                context = context,
                newText = newText,
                previouslyAnswered = previouslyAnswered
            )
        }
    }

    /**
     * Mark segments in the given batch as committed.
     * Keeps committed segments for subsequent context generation.
     */
    fun commit(batchId: String) {
        synchronized(lock) {
            val segments = inFlight.remove(batchId) ?: return
            committed.addAll(segments)
            // Trim committed to avoid unlimited memory growth, keep at most 100 recent segments
            while (committed.size > 100) {
                committed.removeFirst()
            }
        }
    }

    fun commit(segmentIds: List<Long>) {
        synchronized(lock) {
            // Find batch by segment IDs if needed
            val targetBatch = inFlight.entries.firstOrNull { (_, list) ->
                list.map { it.id } == segmentIds
            }
            if (targetBatch != null) {
                commit(targetBatch.key)
            }
        }
    }

    /**
     * If a batch fails, rollback segments back to the front of pending list.
     */
    fun rollback(batchId: String) {
        synchronized(lock) {
            val segments = inFlight.remove(batchId) ?: return
            pending.addAll(0, segments)
        }
    }

    fun rollback(segmentIds: List<Long>) {
        synchronized(lock) {
            val targetBatch = inFlight.entries.firstOrNull { (_, list) ->
                list.map { it.id } == segmentIds
            }
            if (targetBatch != null) {
                rollback(targetBatch.key)
            }
        }
    }

    private fun buildContext(maxChars: Int): String {
        if (committed.isEmpty() || maxChars <= 0) return ""
        val contextSegments = mutableListOf<TranscriptSegment>()
        var currentChars = 0

        // Iterate backwards from the most recent committed segments
        for (i in committed.indices.reversed()) {
            val seg = committed[i]
            contextSegments.add(0, seg)
            currentChars += seg.text.length
            if (currentChars >= maxChars) break
        }

        return contextSegments.joinToString("\n") { "[${it.id}] ${it.text}" }
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            inFlight.clear()
            committed.clear()
        }
    }
}
