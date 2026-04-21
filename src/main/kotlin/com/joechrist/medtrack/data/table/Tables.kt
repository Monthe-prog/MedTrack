package com.joechrist.medtrack.data.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.date

// =============================================================================
// MedTrack – Exposed Table Definitions
// Single source of truth for all table schemas. Mirrors db/init/01_schema.sql
// =============================================================================

object UsersTable : Table("users") {
    val id             = uuid("id")
    val firebaseUid    = varchar("firebase_uid", 128)
    val roleId         = integer("role_id")
    val fullName       = varchar("full_name", 256)
    val email          = varchar("email", 256)
    val phone          = varchar("phone", 32).nullable()
    val dateOfBirth    = date("date_of_birth").nullable()
    val gender         = varchar("gender", 32).nullable()
    val anonAlias      = varchar("anon_alias", 16)
    val avatarObjectKey = varchar("avatar_object_key", 512).nullable()
    val medicalLicenseNo = varchar("medical_license_no", 64).nullable()
    val specialty      = varchar("specialty", 128).nullable()
    val isActive       = bool("is_active")
    val createdAt      = timestampWithTimeZone("created_at")
    val updatedAt      = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object DoctorPatientLinksTable : Table("doctor_patient_links") {
    val id        = uuid("id")
    val doctorId  = uuid("doctor_id")
    val patientId = uuid("patient_id")
    val isActive  = bool("is_active")
    val linkedAt  = timestampWithTimeZone("linked_at")
    override val primaryKey = PrimaryKey(id)
}

object MedicationsTable : Table("medications") {
    val id          = uuid("id")
    val openFdaId   = varchar("openfda_id", 256).nullable()
    val brandName   = varchar("brand_name", 256).nullable()
    val genericName = varchar("generic_name", 256)
    val manufacturer = varchar("manufacturer", 256).nullable()
    val dosageForm  = varchar("dosage_form", 128).nullable()
    val strength    = varchar("strength", 64).nullable()
    val route       = varchar("route", 64).nullable()
    val rawFdaJson  = text("raw_fda_json").nullable()
    val createdAt   = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PrescriptionsTable : Table("prescriptions") {
    val id               = uuid("id")
    val doctorId         = uuid("doctor_id")
    val patientId        = uuid("patient_id")
    val medicationId     = uuid("medication_id")
    val dosageAmount     = decimal("dosage_amount", 8, 2)
    val dosageUnit       = varchar("dosage_unit", 32)
    val frequencyPerDay  = integer("frequency_per_day")
    val durationDays     = integer("duration_days").nullable()
    val instructions     = text("instructions").nullable()
    val scheduleTimes    = text("schedule_times")           // JSON string
    val status           = varchar("status", 16)
    val startDate        = date("start_date")
    val endDate          = date("end_date").nullable()
    val pdfObjectKey     = varchar("pdf_object_key", 512).nullable()
    val createdAt        = timestampWithTimeZone("created_at")
    val updatedAt        = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object IntakeLogsTable : Table("intake_logs") {
    val id             = uuid("id")
    val prescriptionId = uuid("prescription_id")
    val patientId      = uuid("patient_id")
    val scheduledTime  = timestampWithTimeZone("scheduled_time")
    val takenAt        = timestampWithTimeZone("taken_at").nullable()
    val status         = varchar("status", 16)
    val notes          = text("notes").nullable()
    val alarmId        = integer("alarm_id").nullable()
    val createdAt      = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLogsTable : Table("anonymised_audit_logs") {
    val id           = uuid("id")
    val eventType    = varchar("event_type", 64)
    val actorRole    = varchar("actor_role", 32)
    val actorAlias   = varchar("actor_alias", 16)
    val subjectAlias = varchar("subject_alias", 16).nullable()
    val resourceType = varchar("resource_type", 64).nullable()
    val resourceId   = uuid("resource_id").nullable()
    val meta         = text("meta").nullable()              // JSON string
    val occurredAt   = timestampWithTimeZone("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object ChatRoomsTable : Table("chat_rooms") {
    val id        = uuid("id")
    val doctorId  = uuid("doctor_id")
    val patientId = uuid("patient_id")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ChatMessagesTable : Table("chat_messages") {
    val id       = uuid("id")
    val roomId   = uuid("room_id")
    val senderId = uuid("sender_id")
    val content  = text("content")
    val isRead   = bool("is_read")
    val sentAt   = timestampWithTimeZone("sent_at")
    override val primaryKey = PrimaryKey(id)
}
