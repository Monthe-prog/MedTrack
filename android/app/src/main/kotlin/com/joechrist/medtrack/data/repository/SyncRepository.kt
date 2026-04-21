package com.joechrist.medtrack.data.repository

import com.joechrist.medtrack.data.local.dao.*
import com.joechrist.medtrack.data.local.entity.*
import com.joechrist.medtrack.data.remote.MedTrackApiService
import com.joechrist.medtrack.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MedTrack – SyncRepository
// Offline-first data access pattern:
//   READ  → Room (instant, always available)
//   WRITE → Room first (optimistic), then network; failures enqueued for retry
//   SYNC  → Pull latest from server → upsert into Room
// =============================================================================

@Singleton
class SyncRepository @Inject constructor(
    private val prescriptionDao: PrescriptionDao,
    private val intakeLogDao: IntakeLogDao,
    private val medicationDao: MedicationDao,
    private val syncQueueDao: SyncQueueDao,
    private val api: MedTrackApiService,
    private val alarmScheduler: MedicationAlarmScheduler
) {

    // ── Prescriptions ─────────────────────────────────────────────────────────

    /** Live Flow for the UI — always from Room, zero latency. */
    fun observePrescriptions(patientId: String): Flow<List<PrescriptionEntity>> =
        prescriptionDao.observeByPatient(patientId)

    /**
     * Full sync: fetch from server → upsert into Room → schedule alarms.
     * Called by WorkManager on network restore or on foreground refresh.
     */
    suspend fun syncPrescriptions(patientId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val remote = api.getPrescriptionsForPatient(patientId)
            val entities = remote.map { it.toEntity() }
            prescriptionDao.upsertAll(entities)
            // Re-schedule alarms for any newly ACTIVE prescriptions
            scheduleAlarmsForActive(patientId)
        }
    }

    // ── Intake logs ───────────────────────────────────────────────────────────

    /** Live Flow of today's + future scheduled doses. */
    fun observeTodayLogs(patientId: String): Flow<List<IntakeLogEntity>> {
        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return intakeLogDao.observeFromTime(patientId, startOfDay)
    }

    /**
     * Optimistic mark-taken:
     *  1. Write TAKEN to Room immediately (UI updates instantly).
     *  2. Try network call.
     *  3. On failure, enqueue to SyncQueue for background retry.
     */
    suspend fun markTaken(logId: String, notes: String?) = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        // Optimistic write
        intakeLogDao.markTaken(logId, nowMs, notes)

        // Network attempt
        runCatching { api.markDoseTaken(logId, MarkTakenRequest(notes)) }
            .onSuccess { intakeLogDao.markSynced(logId) }
            .onFailure {
                // Enqueue for retry
                syncQueueDao.enqueue(
                    SyncQueueEntity(
                        entityType = "IntakeLog",
                        entityId   = logId,
                        operation  = "MARK_TAKEN",
                        payload    = """{"notes":${if (notes != null) "\"$notes\"" else "null"}}"""
                    )
                )
            }
    }

    /**
     * Optimistic mark-missed. Same pattern as markTaken.
     */
    suspend fun markMissed(logId: String) = withContext(Dispatchers.IO) {
        intakeLogDao.markMissed(logId)
        runCatching { api.markDoseMissed(logId) }
            .onSuccess { intakeLogDao.markSynced(logId) }
            .onFailure {
                syncQueueDao.enqueue(
                    SyncQueueEntity(
                        entityType = "IntakeLog",
                        entityId   = logId,
                        operation  = "MARK_MISSED",
                        payload    = "{}"
                    )
                )
            }
    }

    /** Pull server logs → upsert Room. */
    suspend fun syncIntakeLogs(patientId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val remote = api.getIntakeLogs(patientId)
            intakeLogDao.upsertAll(remote.map { it.toEntity(patientId) })
        }
    }

    // ── Medications ───────────────────────────────────────────────────────────

    suspend fun searchMedications(query: String): List<MedicationEntity> =
        withContext(Dispatchers.IO) {
            // Local first
            val local = medicationDao.search(query)
            if (local.isNotEmpty()) return@withContext local

            // Remote fallback
            val remote = runCatching { api.searchMedications(query) }.getOrElse { emptyList() }
            val entities = remote.map { it.toEntity() }
            medicationDao.upsertAll(entities)
            entities
        }

    // ── Sync queue flush ──────────────────────────────────────────────────────

    /**
     * Drains the sync queue — called by [SyncWorker] when network is available.
     * Items are dequeued on success, retry count incremented on failure.
     * Items exceeding 5 retries are evicted.
     */
    suspend fun flushSyncQueue() = withContext(Dispatchers.IO) {
        val items = syncQueueDao.getAll()
        items.forEach { item ->
            val success = runCatching {
                when (item.operation) {
                    "MARK_TAKEN" -> {
                        val notes = JSONObject(item.payload).optString("notes", "").ifBlank { null }
                        api.markDoseTaken(item.entityId, MarkTakenRequest(notes))
                    }
                    "MARK_MISSED" -> api.markDoseMissed(item.entityId)
                    "STATUS_UPDATE" -> {
                        val status = JSONObject(item.payload).getString("status")
                        api.updatePrescriptionStatus(item.entityId, UpdateStatusRequest(status))
                    }
                    else -> Unit
                }
            }.isSuccess

            if (success) syncQueueDao.dequeue(item.localId)
            else         syncQueueDao.incrementRetry(item.localId)
        }
        syncQueueDao.evictFailed(maxRetries = 5)
    }

    // ── Alarm scheduling ──────────────────────────────────────────────────────

    /**
     * Generates IntakeLog rows + schedules exact alarms for all active
     * prescriptions for the next [daysAhead] days.
     * Safe to call repeatedly — upsert is idempotent.
     */
    suspend fun scheduleAlarmsForActive(patientId: String, daysAhead: Int = 7) =
        withContext(Dispatchers.IO) {
            val activePrescriptions = prescriptionDao.getActiveByPatient(patientId)

            activePrescriptions.forEach { rx ->
                val times = parseScheduleTimes(rx.scheduleTimes)
                val logs  = generateIntakeLogs(rx, times, daysAhead)

                // Upsert logs (won't overwrite TAKEN/MISSED entries due to upsert logic)
                intakeLogDao.upsertAll(logs)

                // Schedule exact alarms for PENDING logs in the upcoming window
                val nowMs = System.currentTimeMillis()
                val cutoff = nowMs + daysAhead * 24 * 60 * 60 * 1000L
                val pending = intakeLogDao.getPendingInRange(patientId, nowMs, cutoff)
                pending.forEach { log ->
                    val alarmId = alarmScheduler.schedule(
                        logId          = log.id,
                        prescriptionId = log.prescriptionId,
                        medicationName = rx.genericName,
                        triggerAtMs    = log.scheduledTimeMs
                    )
                    // Persist alarm ID for later cancellation
                    intakeLogDao.upsert(log.copy(alarmId = alarmId))
                }
            }
        }

    // ── Auto-miss expired doses ───────────────────────────────────────────────

    /** Call on app foreground — marks any PENDING doses >2h overdue as MISSED. */
    suspend fun autoMissExpired() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L   // 2 hours ago
        intakeLogDao.autoMissExpired(cutoff)
    }

    // ── Cache eviction ────────────────────────────────────────────────────────

    suspend fun evictOldData() = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        intakeLogDao.evictOld(thirtyDaysAgo)
        val sevenDaysAgo  = System.currentTimeMillis() -  7L * 24 * 60 * 60 * 1000
        medicationDao.evictStale(sevenDaysAgo)
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun PrescriptionResponse.toEntity() = PrescriptionEntity(
        id                 = id,
        doctorId           = doctorId,
        patientId          = patientId,
        patientDisplayName = patientDisplayName,
        patientAnonAlias   = patientAnonAlias,
        medicationId       = medicationId,
        genericName        = genericName,
        brandName          = brandName,
        dosageAmount       = dosageAmount,
        dosageUnit         = dosageUnit,
        frequencyPerDay    = frequencyPerDay,
        durationDays       = durationDays,
        instructions       = instructions,
        scheduleTimes      = scheduleTimes.joinToString(","),
        status             = status,
        startDate          = startDate,
        endDate            = endDate,
        pdfObjectKey       = pdfObjectKey,
        createdAtMs        = runCatching {
            java.time.OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
        }.getOrElse { System.currentTimeMillis() }
    )

    private fun IntakeLogResponse.toEntity(patientId: String) = IntakeLogEntity(
        id             = id,
        prescriptionId = prescriptionId,
        patientId      = patientId,
        scheduledTimeMs = runCatching {
            java.time.OffsetDateTime.parse(scheduledTime).toInstant().toEpochMilli()
        }.getOrElse { 0L },
        takenAtMs = takenAt?.let {
            runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        },
        status  = status,
        notes   = notes,
        alarmId = null
    )

    private fun MedicationResponse.toEntity() = MedicationEntity(
        id          = id,
        openFdaId   = null,
        brandName   = brandName,
        genericName = genericName,
        manufacturer = null,
        dosageForm  = dosageForm,
        strength    = strength,
        route       = route,
        source      = source
    )

    private fun parseScheduleTimes(json: String): List<String> =
        json.split(",").map { it.trim() }.filter { it.matches(Regex("\\d{2}:\\d{2}")) }

    private fun generateIntakeLogs(
        rx: PrescriptionEntity,
        times: List<String>,
        daysAhead: Int
    ): List<IntakeLogEntity> {
        val today = LocalDate.now()
        val logs  = mutableListOf<IntakeLogEntity>()
        val fmt   = DateTimeFormatter.ofPattern("HH:mm")

        repeat(daysAhead) { dayOffset ->
            val date = today.plusDays(dayOffset.toLong())
            // Respect prescription end date
            rx.endDate?.let { endStr ->
                if (date > LocalDate.parse(endStr)) return@repeat
            }
            times.forEach { timeStr ->
                val time     = LocalTime.parse(timeStr, fmt)
                val dateTime = LocalDateTime.of(date, time)
                val epochMs  = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                // Skip slots already in the past
                if (epochMs <= System.currentTimeMillis()) return@forEach

                logs.add(
                    IntakeLogEntity(
                        // Deterministic ID: no duplicates across re-schedules
                        id             = "${rx.id}_${date}_${timeStr.replace(":", "")}",
                        prescriptionId = rx.id,
                        patientId      = rx.patientId,
                        scheduledTimeMs = epochMs,
                        takenAtMs      = null,
                        status         = "PENDING",
                        notes          = null,
                        alarmId        = null
                    )
                )
            }
        }
        return logs
    }
}
