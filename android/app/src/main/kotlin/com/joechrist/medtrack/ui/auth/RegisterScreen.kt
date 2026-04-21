package com.joechrist.medtrack.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joechrist.medtrack.AuthState
import com.joechrist.medtrack.domain.model.UserRole

// =============================================================================
// MedTrack – Register Screen
// Features: Role selection card (Doctor / Patient) · Full-name · Email · Password
//           Doctor-specific fields (license no., specialty) animate in on selection
// =============================================================================

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onRegisterSuccess: (UserRole) -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState  by viewModel.uiState.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var fullName       by remember { mutableStateOf("") }
    var email          by remember { mutableStateOf("") }
    var password       by remember { mutableStateOf("") }
    var confirmPw      by remember { mutableStateOf("") }
    var selectedRole   by remember { mutableStateOf<UserRole?>(null) }
    var licenseNo      by remember { mutableStateOf("") }
    var specialty      by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            onRegisterSuccess((authState as AuthState.Authenticated).role)
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val isFormValid = fullName.isNotBlank() && email.isNotBlank() &&
        password.length >= 6 && password == confirmPw && selectedRole != null

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                IconButton(
                    onClick  = onBack,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(24.dp)
                ) {
                    Text(
                        "Create account",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Join MedTrack",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            // ── Form ──────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // Role selection
                Text(
                    "I am a…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoleCard(
                        label    = "Patient",
                        icon     = Icons.Default.Person,
                        selected = selectedRole == UserRole.PATIENT,
                        onClick  = { selectedRole = UserRole.PATIENT },
                        modifier = Modifier.weight(1f)
                    )
                    RoleCard(
                        label    = "Doctor",
                        icon     = Icons.Default.LocalHospital,
                        selected = selectedRole == UserRole.DOCTOR,
                        onClick  = { selectedRole = UserRole.DOCTOR },
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Full name
                OutlinedTextField(
                    value = fullName, onValueChange = { fullName = it },
                    label = { Text("Full name") },
                    leadingIcon = { Icon(Icons.Default.Badge, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Email
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() },
                    label = { Text("Email address") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Password
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (min 6 characters)") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Confirm password
                OutlinedTextField(
                    value = confirmPw, onValueChange = { confirmPw = it },
                    label = { Text("Confirm password") },
                    leadingIcon = { Icon(Icons.Default.LockReset, null) },
                    isError = confirmPw.isNotEmpty() && confirmPw != password,
                    supportingText = {
                        if (confirmPw.isNotEmpty() && confirmPw != password)
                            Text("Passwords do not match", color = MaterialTheme.colorScheme.error)
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Doctor-only fields (animated in)
                AnimatedVisibility(visible = selectedRole == UserRole.DOCTOR) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HorizontalDivider()
                        Text(
                            "Doctor details",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = licenseNo, onValueChange = { licenseNo = it },
                            label = { Text("Medical license number") },
                            leadingIcon = { Icon(Icons.Default.CardMembership, null) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = specialty, onValueChange = { specialty = it },
                            label = { Text("Specialty (e.g. Cardiology)") },
                            leadingIcon = { Icon(Icons.Default.Biotech, null) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = {
                        viewModel.createAccountWithEmail(
                            email    = email,
                            password = password,
                            fullName = fullName,
                            role     = selectedRole!!
                        )
                    },
                    enabled  = isFormValid && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color    = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create account", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Role selection card ───────────────────────────────────────────────────────

@Composable
private fun RoleCard(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.secondary
                      else MaterialTheme.colorScheme.outline
    val bgColor     = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                      else MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        color = bgColor
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint   = if (selected) MaterialTheme.colorScheme.secondary
                         else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
