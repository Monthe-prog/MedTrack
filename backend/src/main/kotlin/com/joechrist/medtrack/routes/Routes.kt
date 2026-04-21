package com.joechrist.medtrack.routes

import com.joechrist.medtrack.data.repository.*
import com.joechrist.medtrack.domain.model.UserRole
import com.joechrist.medtrack.plugins.*
import com.joechrist.medtrack.service.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// =============================================================================
// MedTrack – All Route Definitions
// =============================================================================


// ─────────────────────────────────────────────────────────────────────────────
// AUTH ROUTES  /api/v1/auth
// Handles post-Firebase user registration and profile sync.
// Firebase handles the actual authentication; we just keep our DB in sync.
// ─────────────────────────────────────────────────────────────────────────────
fun Route.authRoutes() {
    val userRepo: UserRepository by inject()

    route("/auth") {

        /**
         * POST /auth/register
         * Called once after a new Firebase user signs up.
         * Creates the user row in our DB with the chosen role.
         */
        authenticate("firebase-jwt-no-db") {
            post("/register") {
                val principal = call.principal<FirebasePrincipal>() ?: return@post
                val body = call.receive<RegisterRequest>()

                // Prevent duplicate registrations
                val existing = userRepo.findByFirebaseUid(principal.firebaseUid)
                if (existing != null) {
                    call.respond(HttpStatusCode.Conflict,
                        ApiError("ALREADY_REGISTERED", "User is already registered"))
                    return@post
                }

                val allowedRoles = setOf("DOCTOR", "PATIENT")
                if (body.role !in allowedRoles) {
                    call.respond(HttpStatusCode.BadRequest,
                        ApiError("INVALID_ROLE", "Role must be DOCTOR or PATIENT"))
                    return@post
                }

                // Create user in DB
                val user = userRepo.create(
                    firebaseUid = principal.firebaseUid,
                    fullName    = body.fullName,
                    email       = principal.email,
                    role        = UserRole.valueOf(body.role),
                    medicalLicenseNo = body.medicalLicenseNo,
                    specialty   = body.specialty
                )

                call.respond(HttpStatusCode.Created, mapOf(
                    "message" to "User registered successfully",
                    "id"      to user.id.toString(),
                    "role"    to user.role.name
                ))
            }
        }

        anyAuthenticated {
            /**
             * GET /auth/me
             * Returns the caller's profile (anonymised or full based on role).
             */
            get("/me") {
                val principal = call.requirePrincipal() ?: return@get
                val user = userRepo.findByFirebaseUid(principal.firebaseUid)
                    ?: throw NotFoundException("User profile not found — register first")

                call.respond(mapOf(
                    "id"         to user.id.toString(),
                    "email"      to user.email,
                    "fullName"   to user.fullName,
                    "anonAlias"  to user.anonAlias,
                    "role"       to user.role.name,
                    "isActive"   to user.isActive
                ))
            }
        }
    }
}

@Serializable
data class RegisterRequest(
    val fullName: String,
    val role: String,                // "DOCTOR" | "PATIENT"
    val phone: String?     = null,
    val dateOfBirth: String? = null,
    val medicalLicenseNo: String? = null,
    val specialty: String? = null
)


// ─────────────────────────────────────────────────────────────────────────────
// USER ROUTES  /api/v1/users
// ─────────────────────────────────────────────────────────────────────────────
fun Route.userRoutes() {
    val userRepo: UserRepository by inject()
    val storageService: StorageService by inject()

    route("/users") {

        doctorOnly {
            /**
             * GET /users/patients
             * Doctor sees their own patient list (aliases only — not full PHI).
             */
            get("/patients") {
                val principal = call.requirePrincipal() ?: return@get
                // Returns list of patients linked to this doctor.
                // Full names exposed only in detail view — aliases in list view.
                call.respond(mapOf("message" to "Patient list endpoint — impl in next step"))
            }
        }

        anyAuthenticated {
            /**
             * GET /users/{id}
             * Returns user profile. PHI gating:
             *   - DOCTOR accessing own patient → full PHI
             *   - PATIENT accessing self → full PHI
             *   - PATIENT accessing other → 403
             */
            get("/{id}") {
                val principal = call.requirePrincipal() ?: return@get
                val targetId  = UUID.fromString(call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing user ID"))

                // Ownership / role check
                val allowed = call.requireOwnershipOrDoctor(targetId.toString(), userRepo)
                if (!allowed) return@get

                val user = userRepo.findById(targetId)
                    ?: throw NotFoundException("User not found")

                // PHI gating
                val showFullPhi = principal.role in listOf(UserRole.DOCTOR, UserRole.ADMIN) ||
                    principal.firebaseUid == user.firebaseUid

                call.respond(if (showFullPhi) {
                    mapOf(
                        "id"        to user.id.toString(),
                        "fullName"  to user.fullName,
                        "email"     to user.email,
                        "anonAlias" to user.anonAlias,
                        "role"      to user.role.name
                    )
                } else {
                    mapOf(
                        "anonAlias"  to user.anonAlias,
                        "maskedName" to com.joechrist.medtrack.domain.model.Anonymiser.maskName(user.fullName),
                        "role"       to user.role.name
                    )
                })
            }

            /**
             * POST /users/{id}/avatar
             * Uploads a profile picture. Stored in MinIO.
             */
            post("/{id}/avatar") {
                val principal = call.requirePrincipal() ?: return@post
                val targetId  = call.parameters["id"] ?: throw IllegalArgumentException("Missing ID")

                // Only the user themselves or admin can change avatar
                if (principal.firebaseUid != targetId && principal.role != UserRole.ADMIN) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Cannot update another user's avatar"))
                    return@post
                }

                val multipart = call.receiveMultipart()
                var imageBytes: ByteArray? = null
                var mimeType = "image/jpeg"

                multipart.forEachPart { part ->
                    if (part is io.ktor.http.content.PartData.FileItem) {
                        mimeType   = part.contentType?.toString() ?: "image/jpeg"
                        imageBytes = part.streamProvider().readBytes()
                    }
                    part.dispose()
                }

                val bytes = imageBytes ?: throw IllegalArgumentException("No image provided")
                if (bytes.size > 5 * 1024 * 1024) throw IllegalArgumentException("Image must be < 5 MB")

                val objectKey = storageService.uploadAvatar(targetId, bytes, mimeType)
                val url       = storageService.getAvatarUrl(objectKey)

                call.respond(mapOf("avatarUrl" to url, "objectKey" to objectKey))
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// PRESCRIPTION ROUTES  /api/v1/prescriptions
// ─────────────────────────────────────────────────────────────────────────────
fun Route.prescriptionRoutes() {
    val prescriptionService: PrescriptionService by inject()
    val prescriptionRepo: PrescriptionRepository by inject()
    val userRepo: UserRepository by inject()

    route("/prescriptions") {

        doctorOnly {

            /**
             * POST /prescriptions
             * Create a new prescription.
             * Triggers: DB insert → Gotenberg PDF → MinIO upload → Audit log.
             */
            post {
                val principal = call.requirePrincipal() ?: return@post
                val body = call.receive<CreatePrescriptionRequest>()
                val doctor = userRepo.findByFirebaseUid(principal.firebaseUid)
                    ?: throw NotFoundException("Doctor not found")

                val dto = prescriptionService.createPrescription(
                    doctorFirebaseUid = principal.firebaseUid,
                    doctorAnonAlias   = doctor.anonAlias,
                    request           = body
                )
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * GET /prescriptions/my-patients
             * All prescriptions the doctor has issued.
             */
            get("/my-patients") {
                val principal = call.requirePrincipal() ?: return@get
                val doctor = userRepo.findByFirebaseUid(principal.firebaseUid)
                    ?: throw NotFoundException("Doctor not found")

                val rxList = prescriptionRepo.findByDoctor(doctor.id, UserRole.DOCTOR)
                call.respond(rxList)
            }

            /**
             * PATCH /prescriptions/{id}/status
             * Doctor updates prescription status (PAUSED / COMPLETED / CANCELLED).
             */
            patch("/{id}/status") {
                val principal = call.requirePrincipal() ?: return@patch
                val rxId = UUID.fromString(call.parameters["id"])
                val body = call.receive<UpdateStatusRequest>()
                val doctor = userRepo.findByFirebaseUid(principal.firebaseUid)
                    ?: throw NotFoundException("Doctor not found")

                prescriptionService.updateStatus(rxId, body.status, doctor.anonAlias, UserRole.DOCTOR)
                call.respond(mapOf("message" to "Status updated to ${body.status}"))
            }
        }

        anyAuthenticated {

            /**
             * GET /prescriptions/{id}
             * Any authenticated user can request — ownership checked inside.
             */
            get("/{id}") {
                val principal = call.requirePrincipal() ?: return@get
                val rxId = UUID.fromString(call.parameters["id"])

                val rx = prescriptionRepo.findById(rxId, principal.role)
                    ?: throw NotFoundException("Prescription not found")

                // Patients can only see their own prescriptions
                if (principal.role == UserRole.PATIENT) {
                    val self = userRepo.findByFirebaseUid(principal.firebaseUid)
                    if (self?.id != rx.patientId) {
                        call.respond(HttpStatusCode.Forbidden,
                            ApiError("FORBIDDEN", "Access denied"))
                        return@get
                    }
                }
                call.respond(rx)
            }

            /**
             * GET /prescriptions/{id}/pdf
             * Returns a signed MinIO URL for the prescription PDF.
             */
            get("/{id}/pdf") {
                val principal = call.requirePrincipal() ?: return@get
                val rxId = UUID.fromString(call.parameters["id"])
                val url = prescriptionService.getPrescriptionPdfUrl(rxId, principal.role)
                call.respond(mapOf("pdfUrl" to url, "expiresInMinutes" to 15))
            }

            /**
             * GET /prescriptions/patient/{patientId}
             * List all prescriptions for a patient.
             * Patients can only query themselves; doctors can query their linked patients.
             */
            get("/patient/{patientId}") {
                val principal = call.requirePrincipal() ?: return@get
                val patientId = UUID.fromString(call.parameters["patientId"])

                val allowed = call.requireOwnershipOrDoctor(patientId.toString(), userRepo)
                if (!allowed) return@get

                val statusFilter = call.request.queryParameters["status"]
                val rxList = prescriptionRepo.findByPatient(patientId, principal.role, statusFilter)
                call.respond(rxList)
            }
        }
    }
}

@Serializable data class UpdateStatusRequest(val status: String)


// ─────────────────────────────────────────────────────────────────────────────
// MEDICATION ROUTES  /api/v1/medications
// ─────────────────────────────────────────────────────────────────────────────
fun Route.medicationRoutes() {
    val fdaService: MedicationSearchService by inject()
    val medicationRepo: MedicationRepository by inject()

    route("/medications") {

        anyAuthenticated {

            /**
             * GET /medications/search?q=amoxicillin
             * Searches local cache first, falls back to openFDA.
             */
            get("/search") {
                call.requirePrincipal() ?: return@get
                val q = call.request.queryParameters["q"]?.trim()
                    ?: throw IllegalArgumentException("Query parameter 'q' is required")

                if (q.length < 2) throw IllegalArgumentException("Query must be at least 2 characters")

                // 1. Local cache
                val cached = medicationRepo.search(q, limit = 20)
                if (cached.isNotEmpty()) {
                    val results = cached.map { row ->
                        mapOf(
                            "id"          to row[com.joechrist.medtrack.data.table.MedicationsTable.id].toString(),
                            "genericName" to row[com.joechrist.medtrack.data.table.MedicationsTable.genericName],
                            "brandName"   to row[com.joechrist.medtrack.data.table.MedicationsTable.brandName],
                            "dosageForm"  to row[com.joechrist.medtrack.data.table.MedicationsTable.dosageForm],
                            "strength"    to row[com.joechrist.medtrack.data.table.MedicationsTable.strength],
                            "route"       to row[com.joechrist.medtrack.data.table.MedicationsTable.route],
                            "source"      to "local"
                        )
                    }
                    call.respond(results)
                    return@get
                }

                // 2. openFDA fallback + cache results
                val fdaResults = fdaService.search(q, limit = 10)
                val hydrated = fdaResults.map { drug ->
                    val savedId = medicationRepo.upsertFromFda(
                        openFdaId   = drug.applicationNumber,
                        brandName   = drug.brandName,
                        genericName = drug.genericName,
                        manufacturer = drug.manufacturer,
                        dosageForm  = drug.dosageForm,
                        strength    = drug.strength,
                        route       = drug.route,
                        rawJson     = drug.rawJson
                    )
                    mapOf(
                        "id"          to savedId.toString(),
                        "genericName" to drug.genericName,
                        "brandName"   to drug.brandName,
                        "dosageForm"  to drug.dosageForm,
                        "strength"    to drug.strength,
                        "route"       to drug.route,
                        "indicationsAndUsage" to drug.indicationsAndUsage,
                        "source"      to "openFDA"
                    )
                }
                call.respond(hydrated)
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// INTAKE LOG ROUTES  /api/v1/intake-logs
// ─────────────────────────────────────────────────────────────────────────────
fun Route.intakeLogRoutes() {
    val intakeRepo: IntakeLogRepository by inject()
    val auditRepo: AuditRepository by inject()
    val userRepo: UserRepository by inject()

    route("/intake-logs") {

        anyAuthenticated {

            /**
             * GET /intake-logs/patient/{patientId}
             * Returns recent intake log for a patient.
             */
            get("/patient/{patientId}") {
                val principal = call.requirePrincipal() ?: return@get
                val patientId = UUID.fromString(call.parameters["patientId"])

                val allowed = call.requireOwnershipOrDoctor(patientId.toString(), userRepo)
                if (!allowed) return@get

                val logs = intakeRepo.findByPatient(patientId, limit = 50)
                call.respond(logs.map { row ->
                    mapOf(
                        "id"             to row[com.joechrist.medtrack.data.table.IntakeLogsTable.id].toString(),
                        "prescriptionId" to row[com.joechrist.medtrack.data.table.IntakeLogsTable.prescriptionId].toString(),
                        "scheduledTime"  to row[com.joechrist.medtrack.data.table.IntakeLogsTable.scheduledTime].toString(),
                        "takenAt"        to row[com.joechrist.medtrack.data.table.IntakeLogsTable.takenAt]?.toString(),
                        "status"         to row[com.joechrist.medtrack.data.table.IntakeLogsTable.status],
                        "notes"          to row[com.joechrist.medtrack.data.table.IntakeLogsTable.notes]
                    )
                })
            }

            /**
             * PATCH /intake-logs/{id}/taken
             * Patient marks a dose as taken.
             */
            patch("/{id}/taken") {
                val principal = call.requirePrincipal() ?: return@patch
                val logId = UUID.fromString(call.parameters["id"])
                val body  = call.receiveNullable<MarkTakenRequest>()

                intakeRepo.markTaken(logId, body?.notes)

                val patient = userRepo.findByFirebaseUid(principal.firebaseUid)
                auditRepo.log(
                    eventType    = "INTAKE_TAKEN",
                    actorRole    = principal.role,
                    actorAlias   = patient?.anonAlias ?: "UNKNOWN",
                    resourceType = "IntakeLog",
                    resourceId   = logId
                )
                call.respond(mapOf("message" to "Dose marked as taken"))
            }

            /**
             * PATCH /intake-logs/{id}/missed
             * System or patient marks a dose as missed.
             */
            patch("/{id}/missed") {
                val principal = call.requirePrincipal() ?: return@patch
                val logId = UUID.fromString(call.parameters["id"])

                intakeRepo.markMissed(logId)

                val patient = userRepo.findByFirebaseUid(principal.firebaseUid)
                auditRepo.log(
                    eventType    = "INTAKE_MISSED",
                    actorRole    = principal.role,
                    actorAlias   = patient?.anonAlias ?: "UNKNOWN",
                    resourceType = "IntakeLog",
                    resourceId   = logId
                )
                call.respond(mapOf("message" to "Dose marked as missed"))
            }
        }
    }
}

@Serializable data class MarkTakenRequest(val notes: String? = null)


// ─────────────────────────────────────────────────────────────────────────────
// CHAT ROUTES  /api/v1/chat  (WebSocket + REST history)
// ─────────────────────────────────────────────────────────────────────────────

// In-memory session registry: roomId → set of active WebSocket sessions
private val activeSessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

fun Route.chatRoutes() {
    val userRepo: UserRepository by inject()

    route("/chat") {

        anyAuthenticated {

            /**
             * GET /chat/rooms/{roomId}/history
             * Returns the last 50 messages in a chat room.
             */
            get("/rooms/{roomId}/history") {
                call.requirePrincipal() ?: return@get
                val roomId = call.parameters["roomId"] ?: throw IllegalArgumentException("Missing roomId")
                // Stub — full impl fetches from chat_messages table
                call.respond(mapOf(
                    "roomId"   to roomId,
                    "messages" to emptyList<Any>(),
                    "note"     to "History query — full impl in Step 2"
                ))
            }
        }

        /**
         * WS /chat/ws/{roomId}?token=<firebaseIdToken>
         * WebSocket endpoint for real-time doctor-patient messaging.
         * Token is passed as a query parameter because browsers can't set
         * Authorization headers on WebSocket connections.
         */
        webSocket("/ws/{roomId}") {
            val roomId = call.parameters["roomId"]
                ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing roomId")); return@webSocket }

            // Authenticate via query param token (WS limitation)
            val token = call.request.queryParameters["token"]
                ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing token")); return@webSocket }

            // Minimal auth check — full Firebase verification reused
            val firebaseToken = runCatching {
                com.google.firebase.auth.FirebaseAuth.getInstance().verifyIdToken(token)
            }.getOrNull() ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))
                return@webSocket
            }

            val sender = userRepo.findByFirebaseUid(firebaseToken.uid)
                ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "User not found")); return@webSocket }

            // Register session
            val sessions = activeSessions.getOrPut(roomId) { Collections.synchronizedSet(mutableSetOf()) }
            sessions.add(this)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        // Broadcast to all sessions in the same room
                        val envelope = """{"sender":"${sender.anonAlias}","role":"${sender.role.name}","content":${
                            kotlinx.serialization.json.Json.encodeToString(
                                kotlinx.serialization.builtins.serializer(), text)
                        },"ts":"${java.time.Instant.now()}"}"""

                        sessions.toList().forEach { session ->
                            runCatching { session.send(Frame.Text(envelope)) }
                        }
                        // TODO: persist to chat_messages table
                    }
                }
            } finally {
                sessions.remove(this)
                if (sessions.isEmpty()) activeSessions.remove(roomId)
            }
        }
    }
}
