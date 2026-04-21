package com.joechrist.medtrack.data.remote

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MedTrack – OkHttp Auth Interceptor + Token Authenticator
//
// AuthInterceptor      – attaches the cached Firebase ID token to every request.
// TokenAuthenticator   – triggered by OkHttp on 401; force-refreshes the token
//                        and retries the request ONCE. This handles token
//                        expiry (Firebase tokens last 1h) transparently.
// =============================================================================

@Singleton
class AuthInterceptor @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip auth header for public endpoints (health, open search)
        if (request.header("No-Auth") == "true") {
            return chain.proceed(request.newBuilder().removeHeader("No-Auth").build())
        }

        val token = getCurrentToken()
        val authenticatedRequest = if (token != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else request

        return chain.proceed(authenticatedRequest)
    }

    private fun getCurrentToken(): String? = runBlocking {
        runCatching {
            firebaseAuth.currentUser
                ?.getIdToken(false)     // false = use cached token if not expired
                ?.await()
                ?.token
        }.getOrNull()
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class TokenAuthenticator @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) : Authenticator {

    // Prevents infinite retry loops (max 1 refresh attempt per request)
    override fun authenticate(route: Route?, response: Response): Request? {
        // If we've already tried a refreshed token and still got 401, give up
        if (response.request.header("Authorization-Retry") == "true") return null

        val freshToken = runBlocking {
            runCatching {
                firebaseAuth.currentUser
                    ?.getIdToken(true)      // true = FORCE refresh from Firebase
                    ?.await()
                    ?.token
            }.getOrNull()
        } ?: return null  // User is signed out — stop retrying

        return response.request.newBuilder()
            .header("Authorization", "Bearer $freshToken")
            .header("Authorization-Retry", "true")      // marker to prevent loops
            .build()
    }
}
