package com.joechrist.medtrack.data.local.dao

import androidx.room.*
import com.joechrist.medtrack.data.local.entity.*
import kotlinx.coroutines.flow.Flow

// =============================================================================
// MedTrack – Room DAOs
// =============================================================================

// ── Medication DAO ────────────────────────────────────────────────────────────

@Dao
interface MedicationDao {

    @Upsert
    suspend fun upsertAll(medications: List<MedicationEntity>)

    @Upsert
    suspend fun upsert(medication: MedicationEntity)

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun findById(id: String): MedicationEntity?

    @Query("""
        SELECT * FROM medications
        WHERE LOWER(genericName) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(brandName)   LIKE '%' || LOWER(:query) || '%'
        ORDER BY genericName
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 20): List<MedicationEntity>

    @Query("DELETE FROM medications WHERE cachedAtMs < :beforeMs")
    suspend fun evictStale(beforeMs: Long)
}

// ── Prescription DAO ──────────────────────────────────────────────────────────

@Dao
interface PrescriptionDao {

    @Upsert
    suspend fun upsertAll(prescriptions: List<PrescriptionEntity>)

    @Upsert
    suspend fun upsert(prescription: PrescriptionEntity)

    @Query("SELECT * FROM prescriptions WHERE id = :id")
    suspend fun findById(id: String): PrescriptionEntity?

    /** All prescriptions for the local patient, ordered by createdAt desc. */
    @Query("""
        SELECT * FROM prescriptions
        WHERE patientId = :patientId
        ORDER BY createdAtMs DESC
    """)
    fun observeByPatient(patientId: String): Flow<List<PrescriptionEntity>>

    /** Active-only prescriptions (used for alarm scheduling). */
    @Query("""
        SELECT * FROM prescriptions
        WHERE patientId = :patientId AND status = 'ACTIVE'
    """)
    suspend fun getActiveByPatient(patientId: String): List<PrescriptionEntity>

    @Query("""
        UPDATE prescriptions SET status = :status, isDirty = 1
        WHERE id = :id
    """)
    suspend fun updateStatus(id: String, status: String)

    @Query("""
        UPDATE prescriptions SET pdfObjectKey = :key
        WHERE id = :id
    """)
    suspend fun setPdfKey(id: String, key: String)

    @Query("SELECT * FROM prescriptions WHERE isDirty = 1")
    suspend fun getDirty(): List<PrescriptionEntity>

    @Query("UPDATE prescriptions SET isDirty = 0, syncedAtMs = :nowMs WHERE id = :id")
    suspend fun markSynced(id: String, nowMs: Long = System.currentTimeMillis())
}

// ── Intake Log DAO ────────────────────────────────────────────────────────────

@Dao
interface IntakeLogDao {

    @Upsert
    suspend fun upsertAll(logs: List<IntakeLogEntity>)

    @Upsert
    suspend fun upsert(log: IntakeLogEntity)

    @Query("SELECT * FROM intake_logs WHERE id = :id")
    suspend fun findById(id: String): IntakeLogEntity?

    /** Observe all logs for a patient from a given time (today onwards). */
    @Query("""
        SELECT * FROM intake_logs
        WHERE patientId = :patientId
          AND scheduledTimeMs >= :fromMs
        ORDER BY scheduledTimeMs ASC
    """)
    fun observeFromTime(patientId: String, fromMs: Long): Flow<List<IntakeLogEntity>>

    /** All logs for a specific prescription (for compliance chart). */
    @Query("""
        SELECT * FROM intake_logs
        WHERE prescriptionId = :rxId
        ORDER BY scheduledTimeMs DESC
        LIMIT :limit
    """)
    suspend fun getByPrescription(rxId: String, limit: Int = 60): List<IntakeLogEntity>

    /** Pending logs in a time range — used by AlarmManager scheduler. */
    @Query("""
        SELECT * FROM intake_logs
        WHERE patientId = :patientId
          AND status = 'PENDING'
          AND scheduledTimeMs BETWEEN :fromMs AND :toMs
    """)
    suspend fun getPendingInRange(patientId: String, fromMs: Long, toMs: Long): List<IntakeLogEntity>

    @Query("""
        UPDATE intake_logs
        SET status = 'TAKEN', takenAtMs = :takenAtMs, notes = :notes, isDirty = 1
        WHERE id = :id
    """)
    suspend fun markTaken(id: String, takenAtMs: Long, notes: String?)

    @Query("""
        UPDATE intake_logs
        SET status = 'MISSED', isDirty = 1
        WHERE id = :id
    """)
    suspend fun markMissed(id: String)

    @Query("""
        UPDATE intake_logs
        SET status = 'SKIPPED', isDirty = 1
        WHERE id = :id
    """)
    suspend fun markSkipped(id: String)

    /** Auto-miss any PENDING logs whose scheduledTime is more than 2h in the past. */
    @Query("""
        UPDATE intake_logs
        SET status = 'MISSED', isDirty = 1
        WHERE status = 'PENDING'
          AND scheduledTimeMs < :cutoffMs
    """)
    suspend fun autoMissExpired(cutoffMs: Long)

    @Query("SELECT * FROM intake_logs WHERE isDirty = 1")
    suspend fun getDirty(): List<IntakeLogEntity>

    @Query("UPDATE intake_logs SET isDirty = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM intake_logs WHERE scheduledTimeMs < :beforeMs")
    suspend fun evictOld(beforeMs: Long)
}

// ── User Profile DAO ──────────────────────────────────────────────────────────

@Dao
interface UserProfileDao {

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE firebaseUid = :uid LIMIT 1")
    suspend fun findByFirebaseUid(uid: String): UserProfileEntity?

    @Query("DELETE FROM user_profile")
    suspend fun clear()
}

// ── Sync Queue DAO ────────────────────────────────────────────────────────────

@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue ORDER BY createdAtMs ASC")
    suspend fun getAll(): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE localId = :localId")
    suspend fun dequeue(localId: Long)

    @Query("UPDATE sync_queue SET retryCount = retryCount + 1 WHERE localId = :localId")
    suspend fun incrementRetry(localId: Long)

    @Query("DELETE FROM sync_queue WHERE retryCount >= :maxRetries")
    suspend fun evictFailed(maxRetries: Int = 5)

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeQueueSize(): Flow<Int>
}
