package com.joechrist.medtrack.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

// =============================================================================
// MedTrack – MedicationSearchService
// Queries the openFDA Drug Label endpoint and normalises the response.
// Results are cached into the local `medications` table by the caller.
// Rate limit: 1 000 req/day (anonymous) | 120 000 req/day (with API key)
// =============================================================================

class MedicationSearchService(private val httpClient: HttpClient) {

    private val log = LoggerFactory.getLogger(MedicationSearchService::class.java)

    private val fdaBaseUrl = "https://api.fda.gov/drug/label.json"
    private val apiKey     = System.getenv("OPENFDA_API_KEY")?.takeIf { it.isNotBlank() }

    /**
     * Searches openFDA for drug labels matching [query].
     * Returns up to [limit] normalised results.
     */
    suspend fun search(query: String, limit: Int = 10): List<FdaDrugResult> {
        if (query.isBlank()) return emptyList()

        val params = buildString {
            // Full-text search across brand_name + generic_name + openfda fields
            append("search=openfda.brand_name:\"$query\"+openfda.generic_name:\"$query\"")
            append("&limit=$limit")
            if (apiKey != null) append("&api_key=$apiKey")
        }

        return try {
            val response = httpClient.get("$fdaBaseUrl?$params") {
                accept(ContentType.Application.Json)
            }

            if (!response.status.isSuccess()) {
                log.warn("openFDA returned ${response.status} for query '$query'")
                return emptyList()
            }

            val body = response.body<JsonObject>()
            val results = body["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { it.jsonObject.toFdaResult() }

        } catch (e: Exception) {
            log.error("openFDA search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches a single drug label by its openFDA application number.
     */
    suspend fun fetchById(applicationNumber: String): FdaDrugResult? {
        return try {
            val response = httpClient.get(
                "$fdaBaseUrl?search=openfda.application_number:\"$applicationNumber\"&limit=1"
                    .let { if (apiKey != null) "$it&api_key=$apiKey" else it }
            )
            if (!response.status.isSuccess()) return null
            val body = response.body<JsonObject>()
            body["results"]?.jsonArray?.firstOrNull()?.jsonObject?.toFdaResult()
        } catch (e: Exception) {
            log.error("openFDA fetch by ID failed: ${e.message}")
            null
        }
    }

    // ── Normalise raw FDA JSON → our domain model ─────────────────────────────

    private fun JsonObject.toFdaResult(): FdaDrugResult? {
        val openFda = this["openfda"]?.jsonObject ?: return null

        fun JsonObject.firstString(key: String) =
            this[key]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content

        return FdaDrugResult(
            applicationNumber = openFda.firstString("application_number"),
            brandName         = openFda.firstString("brand_name"),
            genericName       = openFda.firstString("generic_name") ?: return null,
            manufacturer      = openFda.firstString("manufacturer_name"),
            dosageForm        = this.firstString("dosage_forms_and_strengths")
                                ?: openFda.firstString("dosage_form"),
            route             = openFda.firstString("route"),
            strength          = openFda.firstString("strength") ?: extractStrength(this),
            indicationsAndUsage = this["indications_and_usage"]?.jsonArray
                                    ?.firstOrNull()?.jsonPrimitive?.content,
            warnings          = this["warnings"]?.jsonArray
                                    ?.firstOrNull()?.jsonPrimitive?.content,
            rawJson           = this.toString()
        )
    }

    /** Tries to extract a strength string from the warnings/description section. */
    private fun extractStrength(obj: JsonObject): String? =
        obj["description"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?.let { Regex("""(\d+(\.\d+)?\s*(mg|mcg|g|mL|%))""").find(it)?.value }
}

// ── DTO returned to callers ───────────────────────────────────────────────────

@Serializable
data class FdaDrugResult(
    val applicationNumber: String?,
    val brandName: String?,
    val genericName: String,
    val manufacturer: String?,
    val dosageForm: String?,
    val route: String?,
    val strength: String?,
    val indicationsAndUsage: String?,
    val warnings: String?,
    val rawJson: String?          // stored in medications.raw_fda_json for future reference
)
