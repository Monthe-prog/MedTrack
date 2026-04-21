// =============================================================================
// MedTrack – Ktor Backend
// backend/build.gradle.kts
// =============================================================================

val ktor_version: String by project         // 2.3.12
val kotlin_version: String by project       // 2.0.21
val logback_version: String by project      // 1.5.6
val exposed_version: String by project      // 0.52.0
val hikari_version: String by project       // 5.1.0
val postgres_version: String by project     // 42.7.3
val firebase_version: String by project     // 9.3.0
val minio_version: String by project        // 8.5.10
val koin_version: String by project         // 3.5.6

plugins {
    kotlin("jvm") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group   = "com.joechrist.medtrack"
version = "1.0.0"

application {
    mainClass.set("com.joechrist.medtrack.ApplicationKt")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    google()
}

dependencies {

    // ── Ktor Server Core ──────────────────────────────────────────────────────
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-request-validation-jvm:$ktor_version")

    // ── Ktor Client (for openFDA + Gotenberg calls) ───────────────────────────
    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")

    // ── Database – Exposed ORM + HikariCP + PostgreSQL Driver ─────────────────
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("org.postgresql:postgresql:$postgres_version")

    // ── Firebase Admin SDK (JWT verification) ────────────────────────────────
    implementation("com.google.firebase:firebase-admin:$firebase_version")

    // ── MinIO Java SDK ────────────────────────────────────────────────────────
    implementation("io.minio:minio:$minio_version")

    // ── Dependency Injection – Koin ───────────────────────────────────────────
    implementation("io.insert-koin:koin-ktor:$koin_version")
    implementation("io.insert-koin:koin-logger-slf4j:$koin_version")

    // ── Logging ───────────────────────────────────────────────────────────────
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // ── Serialization ────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.12")
}

ktor {
    fatJar {
        archiveFileName.set("medtrack-backend.jar")
    }
}
