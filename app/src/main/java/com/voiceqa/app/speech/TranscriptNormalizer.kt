package com.voiceqa.app.speech

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object TranscriptNormalizer {

    private val recentQuestionHashes = ConcurrentHashMap<String, Long>()

    /**
     * Normalizes a question string by converting to lowercase, stripping punctuation and whitespace.
     */
    fun normalizeQuestion(value: String): String =
        value.lowercase()
            .replace(Regex("[\\s，。！？、,.!?]"), "")
            .trim()

    /**
     * Calculates the SHA-256 hash of the normalized question.
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks whether this question hash has been answered within the time window (default 5 minutes).
     * If not duplicate, records the current timestamp.
     */
    fun isDuplicateAndRecord(
        questionHash: String,
        currentTimeMs: Long = System.currentTimeMillis(),
        windowMs: Long = 5 * 60 * 1000L
    ): Boolean {
        // Prune expired entries first
        val iterator = recentQuestionHashes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value > windowMs) {
                iterator.remove()
            }
        }

        val lastSeen = recentQuestionHashes[questionHash]
        return if (lastSeen != null && (currentTimeMs - lastSeen) <= windowMs) {
            true
        } else {
            recentQuestionHashes[questionHash] = currentTimeMs
            false
        }
    }

    fun clearCache() {
        recentQuestionHashes.clear()
    }
}
