package com.joechrist.medtrack.data.repository

import com.joechrist.medtrack.data.table.UsersTable
import com.joechrist.medtrack.data.table.DoctorPatientLinksTable
import com.joechrist.medtrack.domain.model.DbUser
import com.joechrist.medtrack.domain.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

// =============================================================================
// MedTrack – UserRepository
// Wraps all database operations for the `users` and related tables.
// Uses Exposed DSL with suspend functions (coroutine-safe via Dispatchers.IO).
// =============================================================================

class UserRepository {

    // ── Lookups ──────────────────────────────────────────────────────────────

    suspend fun findByFirebaseUid(uid: String): DbUser? =
        dbQuery {
            UsersTable
                .select { UsersTable.firebaseUid eq uid }
                .singleOrNull()
                ?.toDbUser()
        }

    suspend fun findById(id: UUID): DbUser? =
        dbQuery {
            UsersTable
                .select { UsersTable.id eq id }
                .singleOrNull()
                ?.toDbUser()
        }

    /**
     * Creates a new user in the DB.
     */
    suspend fun create(
        firebaseUid: String,
        fullName: String,
        email: String,
        role: UserRole,
        medicalLicenseNo: String? = null,
        specialty: String? = null
    ): DbUser = dbQuery {
        val newId = UUID.randomUUID()
        val alias = "MT-" + UUID.randomUUID().toString().take(8).uppercase()
        val now = Clock.System.now()

        UsersTable.insert {
            it[id] = newId
            it[UsersTable.firebaseUid] = firebaseUid
            it[roleId] = role.id
            it[UsersTable.fullName] = fullName
            it[UsersTable.email] = email
            it[anonAlias] = alias
            it[isActive] = true
            it[UsersTable.medicalLicenseNo] = medicalLicenseNo
            it[UsersTable.specialty] = specialty
            it[createdAt] = now
            it[updatedAt] = now
        }

        DbUser(
            id = newId,
            firebaseUid = firebaseUid,
            role = role,
            fullName = fullName,
            email = email,
            anonAlias = alias,
            isActive = true
        )
    }

    /**
     * Returns true if the doctor (identified by firebaseUid) has an active
     * link to the given patient UUID.
     */
    suspend fun isDoctorOfPatient(doctorFirebaseUid: String, patientId: String): Boolean =
        dbQuery {
            val doctor = UsersTable
                .select { UsersTable.firebaseUid eq doctorFirebaseUid }
                .singleOrNull() ?: return@dbQuery false

            val doctorUUID = doctor[UsersTable.id]
            val patientUUID = runCatching { UUID.fromString(patientId) }.getOrNull()
                ?: return@dbQuery false

            DoctorPatientLinksTable
                .select {
                    (DoctorPatientLinksTable.doctorId  eq doctorUUID) and
                    (DoctorPatientLinksTable.patientId eq patientUUID)
                }
                .count() > 0
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun ResultRow.toDbUser() = DbUser(
        id          = this[UsersTable.id],
        firebaseUid = this[UsersTable.firebaseUid],
        role        = UserRole.fromId(this[UsersTable.roleId]),
        fullName    = this[UsersTable.fullName],
        email       = this[UsersTable.email],
        anonAlias   = this[UsersTable.anonAlias],
        isActive    = this[UsersTable.isActive]
    )

    private suspend fun <T> dbQuery(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
