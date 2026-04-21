package com.joechrist.medtrack.service

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

// =============================================================================
// MedTrack – PdfService
// Renders prescription HTML templates to PDF via Gotenberg's Chromium endpoint.
// Template uses inline CSS so no external assets are needed (Gotenberg sandbox).
// =============================================================================

class PdfService(private val httpClient: HttpClient) {

    private val log = LoggerFactory.getLogger(PdfService::class.java)
    private val gotenbergUrl = System.getenv("GOTENBERG_URL") ?: "http://gotenberg:3000"

    /**
     * Generates an official prescription PDF.
     *
     * @param data  All fields needed to render the prescription template.
     * @return      Raw PDF bytes ready to be stored in MinIO.
     */
    suspend fun generatePrescriptionPdf(data: PrescriptionPdfData): ByteArray {
        val html = buildPrescriptionHtml(data)

        log.info("Requesting PDF generation for prescription ${data.prescriptionId}")

        val response: HttpResponse = httpClient.submitFormWithBinaryData(
            url = "$gotenbergUrl/forms/chromium/convert/html",
            formData = formData {
                append("files", html.toByteArray(Charsets.UTF_8), Headers.build {
                    append(HttpHeaders.ContentType, "text/html")
                    append(HttpHeaders.ContentDisposition, "filename=\"index.html\"")
                })
                // Paper size: A4
                append("paperWidth",  "8.27")
                append("paperHeight", "11.69")
                // Margins in inches
                append("marginTop",    "1")
                append("marginBottom", "1")
                append("marginLeft",   "0.75")
                append("marginRight",  "0.75")
                // Print background graphics (needed for colored header)
                append("printBackground", "true")
            }
        )

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            log.error("Gotenberg error ${response.status}: $body")
            error("PDF generation failed: ${response.status}")
        }

        return response.readBytes()
    }

    // ── HTML Template ─────────────────────────────────────────────────────────

    private fun buildPrescriptionHtml(d: PrescriptionPdfData): String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>MedTrack Prescription — ${d.prescriptionId}</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Segoe UI', Arial, sans-serif;
      font-size: 13px;
      color: #1a1a2e;
      background: #fff;
    }
    .header {
      background: #0f3460;
      color: #fff;
      padding: 28px 40px 20px;
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
    }
    .header h1 { font-size: 22px; font-weight: 700; letter-spacing: -0.5px; }
    .header .sub { font-size: 11px; opacity: 0.75; margin-top: 4px; }
    .header .rx-id { font-size: 11px; font-family: monospace; opacity: 0.6; }
    .watermark {
      text-align: center;
      padding: 10px 0 2px;
      font-size: 10px;
      letter-spacing: 3px;
      color: #0f3460;
      text-transform: uppercase;
      opacity: 0.4;
    }
    .section {
      padding: 20px 40px;
      border-bottom: 1px solid #eef0f4;
    }
    .section:last-child { border-bottom: none; }
    .section-title {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 1.5px;
      color: #0f3460;
      text-transform: uppercase;
      margin-bottom: 12px;
    }
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .field { margin-bottom: 10px; }
    .field label { display: block; font-size: 10px; color: #888; margin-bottom: 2px; }
    .field span { font-size: 13px; font-weight: 600; color: #1a1a2e; }
    .schedule-grid {
      display: flex; gap: 10px; flex-wrap: wrap; margin-top: 8px;
    }
    .schedule-pill {
      background: #e8f4fd;
      color: #0f3460;
      border-radius: 20px;
      padding: 5px 16px;
      font-size: 13px;
      font-weight: 600;
    }
    .instructions-box {
      background: #f8f9fc;
      border-left: 3px solid #0f3460;
      padding: 12px 16px;
      border-radius: 0 6px 6px 0;
      font-style: italic;
      color: #444;
    }
    .signature-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 40px;
      padding: 32px 40px 24px;
    }
    .sig-block { border-top: 1.5px solid #ccc; padding-top: 8px; }
    .sig-label { font-size: 10px; color: #888; }
    .footer {
      background: #f4f6fa;
      padding: 14px 40px;
      font-size: 10px;
      color: #aaa;
      text-align: center;
    }
    .badge {
      display: inline-block;
      background: #16a34a;
      color: #fff;
      font-size: 9px;
      font-weight: 700;
      letter-spacing: 1px;
      padding: 2px 8px;
      border-radius: 3px;
      margin-left: 8px;
      vertical-align: middle;
    }
  </style>
</head>
<body>

<div class="header">
  <div>
    <h1>MedTrack <span style="font-weight:300;opacity:.7">Medical Prescription</span></h1>
    <div class="sub">Issued via MedTrack Platform · com.joechrist.medtrack</div>
  </div>
  <div class="rx-id">Ref: ${d.prescriptionId}</div>
</div>

<div class="watermark">Official Prescription Document</div>

<!-- Patient -->
<div class="section">
  <div class="section-title">Patient Information</div>
  <div class="two-col">
    <div class="field">
      <label>Full Name</label>
      <span>${d.patientFullName}</span>
    </div>
    <div class="field">
      <label>Date of Birth</label>
      <span>${d.patientDob ?: "—"}</span>
    </div>
    <div class="field">
      <label>Patient ID (Anonymised)</label>
      <span style="font-family:monospace">${d.patientAnonAlias}</span>
    </div>
    <div class="field">
      <label>Issue Date</label>
      <span>${d.issueDate}</span>
    </div>
  </div>
</div>

<!-- Prescribing Doctor -->
<div class="section">
  <div class="section-title">Prescribing Physician</div>
  <div class="two-col">
    <div class="field">
      <label>Name</label>
      <span>Dr. ${d.doctorFullName}</span>
    </div>
    <div class="field">
      <label>License No.</label>
      <span style="font-family:monospace">${d.doctorLicenseNo ?: "—"}</span>
    </div>
    <div class="field">
      <label>Specialty</label>
      <span>${d.doctorSpecialty ?: "General Practice"}</span>
    </div>
  </div>
</div>

<!-- Medication -->
<div class="section">
  <div class="section-title">Medication</div>
  <div class="two-col">
    <div class="field">
      <label>Generic Name</label>
      <span>${d.medicationGenericName}</span>
    </div>
    <div class="field">
      <label>Brand Name</label>
      <span>${d.medicationBrandName ?: "—"}</span>
    </div>
    <div class="field">
      <label>Dosage</label>
      <span>${d.dosageAmount} ${d.dosageUnit}</span>
    </div>
    <div class="field">
      <label>Frequency</label>
      <span>${d.frequencyPerDay}× daily</span>
    </div>
    <div class="field">
      <label>Duration</label>
      <span>${if (d.durationDays != null) "${d.durationDays} days" else "Ongoing"}</span>
    </div>
    <div class="field">
      <label>Status</label>
      <span>${d.status} <span class="badge">ACTIVE</span></span>
    </div>
  </div>

  ${if (!d.scheduleTimes.isNullOrEmpty()) """
  <div class="field" style="margin-top:12px">
    <label>Scheduled Times</label>
    <div class="schedule-grid">
      ${d.scheduleTimes.joinToString("") { "<span class=\"schedule-pill\">$it</span>" }}
    </div>
  </div>
  """ else ""}

  ${if (!d.instructions.isNullOrBlank()) """
  <div class="field" style="margin-top:12px">
    <label>Special Instructions</label>
    <div class="instructions-box">${d.instructions}</div>
  </div>
  """ else ""}
</div>

<!-- Signatures -->
<div class="signature-row">
  <div class="sig-block">
    <div style="height:36px"></div>
    <div class="sig-label">Doctor's Signature &amp; Stamp</div>
  </div>
  <div class="sig-block">
    <div style="height:36px"></div>
    <div class="sig-label">Patient's Signature</div>
  </div>
</div>

<div class="footer">
  This prescription was generated digitally by MedTrack · ${d.issueDate} ·
  Verify at medtrack.joechrist.com/rx/${d.prescriptionId}
</div>

</body>
</html>
    """.trimIndent()
}

// ── Data class for template rendering ────────────────────────────────────────

data class PrescriptionPdfData(
    val prescriptionId: String,
    val issueDate: String,
    // Doctor
    val doctorFullName: String,
    val doctorLicenseNo: String?,
    val doctorSpecialty: String?,
    // Patient (full PHI — only used server-side for PDF, never exposed via API to PATIENT role)
    val patientFullName: String,
    val patientDob: String?,
    val patientAnonAlias: String,
    // Medication
    val medicationGenericName: String,
    val medicationBrandName: String?,
    val dosageAmount: Double,
    val dosageUnit: String,
    val frequencyPerDay: Int,
    val durationDays: Int?,
    val scheduleTimes: List<String>?,
    val instructions: String?,
    val status: String
)
