package com.joechrist.medtrack.plugins

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.joechrist.medtrack.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.io.FileInputStream

// =============================================================================
// MedTrack – Security Module
// File    : plugins/Security.kt
//
// Responsibilities
//  1. Bootstrap Firebase Admin SDK (once per JVM lifetime)
//  2. Register a custom Ktor "bearer" authentication provider that verifies
//     Firebase ID tokens (JWTs) without any third-party JWT lib — Firebase
//     Admin does the heavy lifting (signature + expiry + revocation check).
//  3. Expose RBAC helper extensions so route guards stay declarative.
// =============================================================================

private val log = LoggerFactory.getLogger("MedTrack.Security")

// ---------------------------------------------------------------------------
// 1 · Firebase Admin Bootstrap
// ---------------------------------------------------------------------------

/**
 * Initialise Firebase Admin SDK exactly once.
 * The service-account path / base64 JSON is supplied via ENV so the
 * container image never bakes credentials into layers.
 */
fun initFirebase() {
    if (FirebaseApp.getApps().isNotEmpty()) return  // already initialised (hot-reload)

    val serviceAccountPath = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY")
        ?: error("FIREBASE_SERVICE_ACCOUNT_KEY env var is not set")

    val credentials = if (serviceAccountPath.startsWith("/")) {
        // Path to a mounted JSON file
        GoogleCredentials.fromStream(FileInputStream(serviceAccountPath))
    } else {
        // Inline base64-encoded JSON (CI/CD pipelines)
        val decoded = java.util.Base64.getDecoder().decode(serviceAccountPath)
        GoogleCredentials.fromStream(decoded.inputStream())
    }

    val options = FirebaseOptions.builder()
        .setCredentials(credentials)
        .setProjectId(System.getenv("FIREBASE_PROJECT_ID")
            ?: error("FIREBASE_PROJECT_ID env var is not set"))
        .build()

    FirebaseApp.initializeApp(options)
    log.info("Firebase Admin SDK initialised ✔")
}

// ---------------------------------------------------------------------------
// 2 · Principal – what Ktor "knows" about the authenticated caller
// ---------------------------------------------------------------------------

/**
 * Represents a verified Firebase user attached to a Ktor call.
 *
 * @param firebaseUid  Stable Firebase UID (sub claim).
 * @param email        Verified email from Firebase token.
 * @param role         RBAC role resolved from our database after token verification.
 * @param rawToken     The decoded Firebase token (contains extra claims if needed).
 */
data class FirebasePrincipal(
    val firebaseUid: String,
    val email: String,
    val role: UserRole,
    val rawToken: FirebaseToken
) : Principal

// ---------------------------------------------------------------------------
// 3 · Custom Ktor Auth Provider
// ---------------------------------------------------------------------------

/**
 * Name constants for the authentication configurations.
 * Use these in `authenticate("…") { }` blocks.
 */
object AuthScheme {
    const val FIREBASE = "firebase-jwt"     // any authenticated user
    const val DOCTOR   = "role-doctor"      // doctors + admins only
    const val PATIENT  = "role-patient"     // patients only (own data)
    const val ADMIN    = "role-admin"       // platform admins only
}

/**
 * Registers all authentication providers on the Ktor application.
 * Called from [Application.module].
 */
fun Application.configureSecurity() {

    initFirebase()

    // We inject the UserRepository to resolve the DB role after token verify.
    // Using Koin to get the dependency.
    val userRepository: com.joechrist.medtrack.data.repository.UserRepository by
        inject(com.joechrist.medtrack.data.repository.UserRepository::class)

    authentication {

        // Base provider: verifies the Firebase JWT, resolves the DB role.
        // All role-specific providers below delegate to this one.
        // ------------------------------------------------------------------
        bearer(AuthScheme.FIREBASE) {
            realm = "MedTrack API"
            authenticate { tokenCredential ->
                verifyFirebaseToken(tokenCredential.token, userRepository)
            }
        }

        // Token-only provider (no DB check) - for /auth/register
        // We use a different name "firebase-jwt-no-db" to avoid 401 retries
        // for valid tokens that just aren't in the DB yet.
        bearer("firebase-jwt-no-db") {
            realm = "MedTrack API"
            authenticate { tokenCredential ->
                verifyFirebaseToken(tokenCredential.token, userRepository, skipDbCheck = true)
            }
        }

        // Doctor (+ Admin) access
        bearer(AuthScheme.DOCTOR) {
            realm = "MedTrack Doctor Panel"
            authenticate { tokenCredential ->
                verifyFirebaseToken(tokenCredential.token, userRepository)
                    ?.takeIf { it.role in listOf(UserRole.DOCTOR, UserRole.ADMIN) }
            }
        }

        // Patient-only access
        bearer(AuthScheme.PATIENT) {
            realm = "MedTrack Patient Portal"
            authenticate { tokenCredential ->
                verifyFirebaseToken(tokenCredential.token, userRepository)
                    ?.takeIf { it.role == UserRole.PATIENT }
            }
        }

        // Admin-only access
        bearer(AuthScheme.ADMIN) {
            realm = "MedTrack Admin Console"
            authenticate { tokenCredential ->
                verifyFirebaseToken(tokenCredential.token, userRepository)
                    ?.takeIf { it.role == UserRole.ADMIN }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4 · Core Token Verification Logic
// ---------------------------------------------------------------------------

/**
 * Verifies a raw Firebase ID-token string and resolves the caller's DB role.
 *
 * Steps:
 *  a) [FirebaseAuth.verifyIdToken] validates the RS256 signature,
 *     expiry, audience (projectId), and checks the revocation list.
 *  b) We then look up the user in our own database to get the RBAC role.
 *     This ensures that if an admin changes a role, the next API call
 *     reflects it (no stale role in the token claims).
 *
 * Returns null on any failure → Ktor responds with 401 automatically.
 */
private suspend fun verifyFirebaseToken(
    rawToken: String,
    userRepository: com.joechrist.medtrack.data.repository.UserRepository,
    skipDbCheck: Boolean = false
): FirebasePrincipal? = runCatching {

    // a) Cryptographic + expiry verification
    val decoded: FirebaseToken = FirebaseAuth.getInstance()
        .verifyIdToken(rawToken, /* checkRevoked = */ true)

    val firebaseUid = decoded.uid
    val email = decoded.email ?: ""

    if (skipDbCheck) {
        return FirebasePrincipal(
            firebaseUid = firebaseUid,
            email       = email,
            role        = UserRole.PATIENT, // Temporary role for registration context
            rawToken    = decoded
        )
    }

    // b) Resolve role from our database
    val dbUser = userRepository.findByFirebaseUid(firebaseUid)
        ?: run {
            if (skipDbCheck) return@run null // Should not happen with skipDbCheck=true
            log.warn("Token valid but user not found in DB: uid=$firebaseUid")
            throw UserNotRegisteredException("User profile not found in DB - please register")
        }

    if (!dbUser.isActive) {
        log.warn("Blocked inactive user: uid=$firebaseUid")
        return@runCatching null
    }

    FirebasePrincipal(
        firebaseUid = firebaseUid,
        email       = email,
        role        = dbUser.role,
        rawToken    = decoded
    )
}.onFailure { e ->
    log.warn("Firebase token verification failed: ${e.message}")
}.getOrNull()

// ---------------------------------------------------------------------------
// 5 · RBAC Route Extension Helpers
// ---------------------------------------------------------------------------

/**
 * Retrieves the [FirebasePrincipal] from the call or responds 401.
 * Use inside authenticated routes.
 *
 * ```kotlin
 * get("/me") {
 *     val principal = call.requirePrincipal() ?: return@get
 *     call.respond(principal.email)
 * }
 * ```
 */
suspend fun ApplicationCall.requirePrincipal(): FirebasePrincipal? {
    val p = principal<FirebasePrincipal>()
    if (p == null) respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
    return p
}

/**
 * Guards a route so that a DOCTOR may only access data belonging to
 * their own patient list, and a PATIENT may only access their own records.
 *
 * @param targetUserId The UUID of the resource owner extracted from the path.
 */
suspend fun ApplicationCall.requireOwnershipOrDoctor(
    targetUserId: String,
    userRepository: com.joechrist.medtrack.data.repository.UserRepository
): Boolean {
    val principal = requirePrincipal() ?: return false

    return when (principal.role) {
        UserRole.ADMIN  -> true
        UserRole.DOCTOR -> userRepository.isDoctorOfPatient(
            doctorFirebaseUid  = principal.firebaseUid,
            patientId          = targetUserId
        )
        UserRole.PATIENT -> {
            val self = userRepository.findByFirebaseUid(principal.firebaseUid)
            val allowed = self?.id.toString() == targetUserId
            if (!allowed) respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            allowed
        }
    }
}

// ---------------------------------------------------------------------------
// Domain exceptions (caught by StatusPages)
// ---------------------------------------------------------------------------
class UserNotRegisteredException(message: String) : Exception(message)

/**
 * Convenience wrapper for doctor-only route blocks.
 *
 * ```kotlin
 * doctorOnly {
 *     get("/patients/{id}/phi") { … }
 * }
 * ```
 */
fun Route.doctorOnly(build: Route.() -> Unit): Route =
    authenticate(AuthScheme.DOCTOR) { build() }

fun Route.patientOnly(build: Route.() -> Unit): Route =
    authenticate(AuthScheme.PATIENT) { build() }

fun Route.adminOnly(build: Route.() -> Unit): Route =
    authenticate(AuthScheme.ADMIN) { build() }

fun Route.anyAuthenticated(build: Route.() -> Unit): Route =
    authenticate(AuthScheme.FIREBASE) { build() }
