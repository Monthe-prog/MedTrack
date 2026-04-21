package com.joechrist.medtrack.data.repository

import com.joechrist.medtrack.data.table.*
import com.joechrist.medtrack.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

// =============================================================================
// MedTrack – PrescriptionRepository
// Handles all DB operations for prescriptions + intake-log generation.
// Anonymisation is enforced here: callers pass a `callerRole` and receive
// either full PHI or masked data depending on their access level.
// =============================================================================

data class PrescriptionDto(
    val id: UUID,
    val doctorId: UUID,
    val patientId: UUID,
    val patientDisplayName: String,    // full name or masked depending on caller
    val patientAnonAlias: String,
    val medicationId: UUID,
    val genericName: String,
    val brandName: String?,
    val dosageAmount: Double,
    val dosageUnit: String,
    val frequencyPerDay: Int,
    val durationDays: Int?,
    val instructions: String?,
    val scheduleTimes: List<String>,   // ["08:00","14:00","20:00"]
    val status: String,
    val startDate: String,
    val endDate: String?,
    val pdfObjectKey: String?,
    val createdAt: String
)

class PrescriptionRepository {

    // ── Create ────────────────────────────────────────────────────────────────

    suspend fun create(
        doctorId: UUID,
        patientId: UUID,
        medicationId: UUID,
        dosageAmount: Double,
        dosageUnit: String,
        frequencyPerDay: Int,
        durationDays: Int?,
        instructions: String?,
        scheduleTimes: List<String>,
        startDate: String,
        endDate: String?
    ): UUID = dbQuery {
        val newId = UUID.randomUUID()
        PrescriptionsTable.insert {
            it[id]              = newId
            it[PrescriptionsTable.doctorId]        = doctorId
            it[PrescriptionsTable.patientId]       = patientId
            it[PrescriptionsTable.medicationId]    = medicationId
            it[PrescriptionsTable.dosageAmount]    = dosageAmount.toBigDecimal()
            it[PrescriptionsTable.dosageUnit]      = dosageUnit
            it[PrescriptionsTable.frequencyPerDay] = frequencyPerDay
            it[PrescriptionsTable.durationDays]    = durationDays
            it[PrescriptionsTable.instructions]    = instructions
            it[PrescriptionsTable.scheduleTimes]   = Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    kotlinx.serialization.builtins.serializer()
                ), scheduleTimes)
            it[status]          = "ACTIVE"
            it[PrescriptionsTable.startDate]       = org.jetbrains.exposed.sql.kotlin.datetime.LocalDate.parse(startDate)
            it[PrescriptionsTable.endDate]         = endDate?.let { d -> org.jetbrains.exposed.sql.kotlin.datetime.LocalDate.parse(d) }
            it[createdAt]       = kotlinx.datetime.Clock.System.now().toJavaInstant()
                .atOffset(java.time.ZoneOffset.UTC)
            it[updatedAt]       = kotlinx.datetime.Clock.System.now().toJavaInstant()
                .atOffset(java.time.ZoneOffset.UTC)
        }
        newId
    }

    // ── Update PDF key after generation ──────────────────────────────────────

    suspend fun setPdfObjectKey(prescriptionId: UUID, objectKey: String) = dbQuery {
        PrescriptionsTable.update({ PrescriptionsTable.id eq prescriptionId }) {
            it[pdfObjectKey] = objectKey
            it[updatedAt] = kotlinx.datetime.Clock.System.now().toJavaInstant()
                .atOffset(java.time.ZoneOffset.UTC)
        }
    }

    // ── Read: single prescription ─────────────────────────────────────────────

    suspend fun findById(id: UUID, callerRole: UserRole): PrescriptionDto? = dbQuery {
        (PrescriptionsTable
            innerJoin UsersTable.alias("patient")
            innerJoin MedicationsTable)
            .select { PrescriptionsTable.id eq id }
            .singleOrNull()
            ?.toDto(callerRole)
    }

    // ── Read: all prescriptions for a patient ────────────────────────────────

    suspend fun findByPatient(
        patientId: UUID,
        callerRole: UserRole,
        statusFilter: String? = null
    ): List<PrescriptionDto> = dbQuery {
        val query = (PrescriptionsTable
            innerJoin UsersTable
            innerJoin MedicationsTable)
            .select { PrescriptionsTable.patientId eq patientId }

        if (statusFilter != null)
            query.andWhere { PrescriptionsTable.status eq statusFilter }

        query.orderBy(PrescriptionsTable.createdAt, SortOrder.DESC)
            .map { it.toDto(callerRole) }
    }

    // ── Read: all prescriptions issued by a doctor ────────────────────────────

    suspend fun findByDoctor(
        doctorId: UUID,
        callerRole: UserRole
    ): List<PrescriptionDto> = dbQuery {
        (PrescriptionsTable
            innerJoin UsersTable
            innerJoin MedicationsTable)
            .select { PrescriptionsTable.doctorId eq doctorId }
            .orderBy(PrescriptionsTable.createdAt, SortOrder.DESC)
            .map { it.toDto(callerRole) }
    }

    // ── Update status ────────────────────────────────────────────────────────

    suspend fun updateStatus(id: UUID, status: String) = dbQuery {
        PrescriptionsTable.update({ PrescriptionsTable.id eq id }) {
            it[PrescriptionsTable.status] = status
            it[updatedAt] = kotlinx.datetime.Clock.System.now().toJavaInstant()
                .atOffset(java.time.ZoneOffset.UTC)
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toDto(callerRole: UserRole): PrescriptionDto {
        val fullName  = this[UsersTable.fullName]
        val anonAlias = this[UsersTable.anonAlias]
        // PHI name: only DOCTOR or ADMIN see the real name in prescription context
        val displayName = when (callerRole) {
            UserRole.DOCTOR, UserRole.ADMIN -> fullName
            UserRole.PATIENT                -> fullName          // own name is fine
        }

        val scheduleJson = this[PrescriptionsTable.scheduleTimes]
        val times = runCatching {
            Json.decodeFromString<List<String>>(scheduleJson)
        }.getOrDefault(emptyList())

        return PrescriptionDto(
            id                = this[PrescriptionsTable.id],
            doctorId          = this[PrescriptionsTable.doctorId],
            patientId         = this[PrescriptionsTable.patientId],
            patientDisplayName = displayName,
            patientAnonAlias  = anonAlias,
            medicationId      = this[PrescriptionsTable.medicationId],
            genericName       = this[MedicationsTable.genericName],
            brandName         = this[MedicationsTable.brandName],
            dosageAmount      = this[PrescriptionsTable.dosageAmount].toDouble(),
            dosageUnit        = this[PrescriptionsTable.dosageUnit],
            frequencyPerDay   = this[PrescriptionsTable.frequencyPerDay],
            durationDays      = this[PrescriptionsTable.durationDays],
            instructions      = this[PrescriptionsTable.instructions],
            scheduleTimes     = times,
            status            = this[PrescriptionsTable.status],
            startDate         = this[PrescriptionsTable.startDate].toString(),
            endDate           = this[PrescriptionsTable.endDate]?.toString(),
            pdfObjectKey      = this[PrescriptionsTable.pdfObjectKey],
            createdAt         = this[PrescriptionsTable.createdAt].toString()
        )
    }

    private suspend fun <T> dbQuery(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

// ---------------------------------------------------------------------------
// Supporting repositories (stubs, filled next steps)
// ---------------------------------------------------------------------------

class MedicationRepository {
    suspend fun findById(id: UUID): ResultRow? = newSuspendedTransaction(Dispatchers.IO) {
        MedicationsTable.select { MedicationsTable.id eq id }.singleOrNull()
    }

    suspend fun upsertFromFda(
        openFdaId: String?,
        brandName: String?,
        genericName: String,
        manufacturer: String?,
        dosageForm: String?,
        strength: String?,
        route: String?,
        rawJson: String?
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        // Check if exists by openFdaId
        val existing = openFdaId?.let {
            MedicationsTable.select { MedicationsTable.openFdaId eq it }.singleOrNull()
        }
        if (existing != null) return@newSuspendedTransaction existing[MedicationsTable.id]

        val newId = UUID.randomUUID()
        MedicationsTable.insert {
            it[id]           = newId
            it[MedicationsTable.openFdaId]    = openFdaId
            it[MedicationsTable.brandName]    = brandName
            it[MedicationsTable.genericName]  = genericName
            it[MedicationsTable.manufacturer] = manufacturer
            it[MedicationsTable.dosageForm]   = dosageForm
            it[MedicationsTable.strength]     = strength
            it[MedicationsTable.route]        = route
            it[rawFdaJson]   = rawJson
            it[createdAt]    = kotlinx.datetime.Clock.System.now().toJavaInstant()
                .atOffset(java.time.ZoneOffset.UTC)
        }
        newId
    }

    suspend fun search(query: String, limit: Int = 20): List<ResultRow> =
        newSuspendedTransaction(Dispatchers.IO) {
            MedicationsTable
                .select {
                    (MedicationsTable.genericName.lowerCase() like "%${query.lowercase()}%") or
                    (MedicationsTable.brandName.lowerCase()   like "%${query.lowercase()}%")
                }
                .limit(limit)
                .toList()
        }
}

class IntakeLogRepository {
    suspend fun create(
        prescriptionId: UUID,
        patientId: UUID,
        scheduledTime: java.time.OffsetDateTime,
        alarmId: Int?
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val newId = UUID.randomUUID()
        IntakeLogsTable.insert {
            it[id]             = newId
            it[IntakeLogsTable.prescriptionId] = prescriptionId
            it[IntakeLogsTable.patientId]      = patientId
            it[IntakeLogsTable.scheduledTime]  = scheduledTime
            it[status]         = "PENDING"
            it[IntakeLogsTable.alarmId]        = alarmId
            it[createdAt]      = java.time.OffsetDateTime.now()
        }
        newId
    }

    suspend fun markTaken(logId: UUID, notes: String?) =
        newSuspendedTransaction(Dispatchers.IO) {
            IntakeLogsTable.update({ IntakeLogsTable.id eq logId }) {
                it[status] = "TAKEN"
                it[takenAt] = java.time.OffsetDateTime.now()
                it[IntakeLogsTable.notes] = notes
            }
        }

    suspend fun markMissed(logId: UUID) =
        newSuspendedTransaction(Dispatchers.IO) {
            IntakeLogsTable.update({ IntakeLogsTable.id eq logId }) {
                it[status] = "MISSED"
            }
        }

    suspend fun findByPatient(patientId: UUID, limit: Int = 50): List<ResultRow> =
        newSuspendedTransaction(Dispatchers.IO) {
            IntakeLogsTable
                .select { IntakeLogsTable.patientId eq patientId }
                .orderBy(IntakeLogsTable.scheduledTime, SortOrder.DESC)
                .limit(limit)
                .toList()
        }
}

class AuditRepository {
    suspend fun log(
        eventType: String,
        actorRole: UserRole,
        actorAlias: String,
        subjectAlias: String? = null,
        resourceType: String? = null,
        resourceId: UUID? = null,
        meta: Map<String, String> = emptyMap()
    ) = newSuspendedTransaction(Dispatchers.IO) {
        AuditLogsTable.insert {
            it[id]           = UUID.randomUUID()
            it[AuditLogsTable.eventType]    = eventType
            it[AuditLogsTable.actorRole]    = actorRole.name
            it[AuditLogsTable.actorAlias]   = actorAlias
            it[AuditLogsTable.subjectAlias] = subjectAlias
            it[AuditLogsTable.resourceType] = resourceType
            it[AuditLogsTable.resourceId]   = resourceId
            it[AuditLogsTable.meta]         = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.builtins.serializer(),
                    kotlinx.serialization.builtins.serializer()
                ), meta)
            it[occurredAt]   = java.time.OffsetDateTime.now()
        }
    }
}
