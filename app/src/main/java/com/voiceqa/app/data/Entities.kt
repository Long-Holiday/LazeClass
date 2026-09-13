package com.voiceqa.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SegmentStatus {
    PENDING,
    IN_FLIGHT,
    COMMITTED
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val language: String = "zh-CN"
)

@Entity(
    tableName = "transcript_segments",
    indices = [
        Index("sessionId"),
        Index("status")
    ]
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val status: SegmentStatus = SegmentStatus.PENDING
)

@Entity(
    tableName = "answers",
    indices = [
        Index("sessionId"),
        Index("normalizedQuestionHash")
    ]
)
data class AnswerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val batchId: String,
    val question: String,
    val normalizedQuestionHash: String,
    val answer: String,
    val createdAt: Long = System.currentTimeMillis()
)
