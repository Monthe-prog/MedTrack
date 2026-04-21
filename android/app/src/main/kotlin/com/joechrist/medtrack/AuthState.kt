package com.joechrist.medtrack

import com.joechrist.medtrack.domain.model.UserRole

// =============================================================================
// MedTrack – AuthState
// Sealed class representing every possible authentication state.
// Observed by MainActivity and the NavHost to drive navigation.
// =============================================================================

sealed class AuthState {
    /** Firebase auth state is still resolving (splash screen stays visible). */
    data object Loading : AuthState()

    /** No authenticated user. */
    data object Unauthenticated : AuthState()

    /** User is authenticated; `role` drives which NavGraph branch to show. */
    data class Authenticated(val role: UserRole) : AuthState()
}
