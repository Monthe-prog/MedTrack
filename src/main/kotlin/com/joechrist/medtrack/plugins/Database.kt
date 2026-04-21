package com.joechrist.medtrack.plugins

import com.joechrist.medtrack.data.repository.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MedTrack.Database")

fun Application.configureDatabase() {
    val config = HikariConfig().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = "jdbc:postgresql://${env("DB_HOST")}:${env("DB_PORT")}/${env("DB_NAME")}"
        username = env("DB_USER")
        password = env("DB_PASSWORD")
        maximumPoolSize = 10
        minimumIdle = 2
        idleTimeout = 600_000
        connectionTimeout = 30_000
        validationTimeout = 3_000
        leakDetectionThreshold = 60_000
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }

    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)
    log.info("Database connected ✔ (pool size: 10)")
}

private fun env(key: String) =
    System.getenv(key) ?: error("Missing required env var: $key")
