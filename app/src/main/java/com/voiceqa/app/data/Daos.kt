package com.voiceqa.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM sessions WHERE startedAt < :cutoffTimestamp")
    suspend fun deleteSessionsOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM sessions WHERE id != :keepSessionId")
    suspend fun deleteOtherSessions(keepSessionId: String)

    @Query("DELETE FROM sessions")
    suspend fun clearAll()
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insertSegment(segment: TranscriptSegmentEntity): Long

    @Insert
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>): List<Long>

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeSegmentsForSession(sessionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSegmentsForSession(sessionId: String): List<TranscriptSegmentEntity>

    @Query("UPDATE transcript_segments SET status = :newStatus WHERE id IN (:segmentIds)")
    suspend fun updateSegmentStatus(segmentIds: List<Long>, newStatus: SegmentStatus)

    @Query("UPDATE transcript_segments SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun recoverInFlightToPending()

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsBySession(sessionId: String)

    @Query("DELETE FROM transcript_segments WHERE startTimeMs < :cutoffTimestamp")
    suspend fun deleteSegmentsOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM transcript_segments WHERE sessionId != :keepSessionId")
    suspend fun deleteOtherSegments(keepSessionId: String)

    @Query("DELETE FROM transcript_segments")
    suspend fun clearAll()
}

@Dao
interface AnswerDao {
    @Insert
    suspend fun insertAnswer(answer: AnswerEntity): Long

    @Insert
    suspend fun insertAnswers(answers: List<AnswerEntity>): List<Long>

    @Query("SELECT * FROM answers WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeAnswersForSession(sessionId: String): Flow<List<AnswerEntity>>

    @Query("SELECT * FROM answers ORDER BY createdAt DESC")
    fun observeAllAnswers(): Flow<List<AnswerEntity>>

    @Query("SELECT question FROM answers WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentQuestionsForSession(sessionId: String, limit: Int = 10): List<String>

    @Query("DELETE FROM answers WHERE sessionId = :sessionId")
    suspend fun deleteAnswersBySession(sessionId: String)

    @Query("DELETE FROM answers WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteAnswersOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM answers WHERE sessionId != :keepSessionId")
    suspend fun deleteOtherAnswers(keepSessionId: String)

    @Query("DELETE FROM answers")
    suspend fun clearAll()
}
