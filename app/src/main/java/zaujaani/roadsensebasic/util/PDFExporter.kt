package zaujaani.roadsensebasic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import zaujaani.roadsensebasic.data.local.entity.*
import zaujaani.roadsensebasic.domain.engine.SDICalculator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import zaujaani.roadsensebasic.data.local.entity.SegmentPci
import zaujaani.roadsensebasic.data.local.entity.PCIDistressItem
import zaujaani.roadsensebasic.domain.engine.PCIRating
import zaujaani.roadsensebasic.data.local.entity.SurveyMode

/**
 * PDFExporter v3 — Professional Multi-page PDF Report
 *
 * FIX v3:
 * - FIX W5: STA format (1500m → STA 1+500, bukan STA 15+0)
 * - FIX #7: PCI report sekarang profesional:
 *   • Distribusi kondisi per kategori PCIRating (Sangat Baik, Baik, dll)
 *   • Action required per segmen
 *   • Detail distress per segmen (type, severity, density, DV)
 *   • Keterangan metodologi ASTM D6433
 *   • Rekomendasi berdasarkan rating
 */
object PDFExporter {

    private const val PAGE_W        = 595f
    private const val PAGE_H        = 842f
    private const val MARGIN_L      = 50f
    private const val MARGIN_R      = 50f
    private const val MARGIN_TOP    = 60f
    private const val MARGIN_BOTTOM = 60f
    private const val LINE_H        = 18f
    private const val SECTION_GAP   = 14f
    private const val THUMB_W       = 80f
    private const val THUMB_H       = 60f
    private const val THUMB_GAP     = 8f

    private val contentWidth get() = PAGE_W - MARGIN_L - MARGIN_R

    private val dateFormat     = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val paintTitle = Paint().apply {
        color = Color.parseColor("#1565C0"); textSize = 20f; isFakeBoldText = true; isAntiAlias = true
    }
    private val paintHeader = Paint().apply {
        color = Color.parseColor("#1976D2"); textSize = 14f; isFakeBoldText = true; isAntiAlias = true
    }
    private val paintBody = Paint().apply {
        color = Color.BLACK; textSize = 11f; isAntiAlias = true
    }
    private val paintSmall = Paint().apply {
        color = Color.DKGRAY; textSize = 9f; isAntiAlias = true
    }
    private val paintDivider = Paint().apply {
        color = Color.LTGRAY; strokeWidth = 1f; style = Paint.Style.STROKE
    }

    private var pdfDocument: PdfDocument = PdfDocument()
    private var currentPage: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = MARGIN_TOP
    private var pageNum = 0
    private var sessionTitle = ""

    // ── Public API ────────────────────────────────────────────────────────

    fun exportToPdf(
        context: Context,
        session: SurveySession,
        telemetries: List<TelemetryRaw>       = emptyList(),
        events: List<RoadEvent>               = emptyList(),
        segmentsSdi: List<SegmentSdi>         = emptyList(),
        distressItems: List<DistressItem>     = emptyList(),
        conditionDistribution: Map<String, Double> = emptyMap(),
        surfaceDistribution: Map<String, Double>   = emptyMap(),
        segmentsPci: List<SegmentPci>         = emptyList(),
        pciDistressItems: List<PCIDistressItem>    = emptyList()
    ): File? {
        val fileName = "RoadSense_${fileNameFormat.format(Date())}.pdf"
        val dir = context.getExternalFilesDir("Reports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        // Reset state
        pdfDocument = PdfDocument()
        currentPage = null
        canvas = null
        y = MARGIN_TOP
        pageNum = 0
        sessionTitle = session.roadName.ifBlank { "RoadSense Survey" }

        startNewPage()

        // Cover / header info
        drawCoverHeader(session)
        drawDivider()

        // Mode-specific report sections
        when (session.mode) {
            SurveyMode.SDI ->
                drawSdiReport(session, segmentsSdi, distressItems, telemetries)
            SurveyMode.PCI ->
                drawPciReport(session, segmentsPci, pciDistressItems, telemetries)
            SurveyMode.GENERAL ->
                drawGeneralReport(session, conditionDistribution, surfaceDistribution, telemetries)
        }

        // Events & photos (semua mode)
        if (events.isNotEmpty()) drawEventSection(events)

        // Finish
        finishPage()
        return try {
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
            pdfDocument.close()
            file
        } catch (e: IOException) {
            pdfDocument.close()
            null
        }
    }

    // ── Pages ─────────────────────────────────────────────────────────────

    private fun startNewPage() {
        finishPage()
        pageNum++
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNum).create()
        currentPage = pdfDocument.startPage(pageInfo)
        canvas = currentPage!!.canvas
        y = MARGIN_TOP

        // Header bar
        canvas?.drawText(sessionTitle, MARGIN_L, 20f, paintSmall)
        canvas?.drawText("Hal. $pageNum", PAGE_W - MARGIN_R - 40f, 20f, paintSmall)
        canvas?.drawLine(MARGIN_L, 26f, PAGE_W - MARGIN_R, 26f, paintDivider)
        y = MARGIN_TOP + 10f
    }

    private fun finishPage() {
        currentPage?.let {
            // Footer
            canvas?.drawLine(MARGIN_L, PAGE_H - MARGIN_BOTTOM + 10f, PAGE_W - MARGIN_R, PAGE_H - MARGIN_BOTTOM + 10f, paintDivider)
            canvas?.drawText("Generated by RoadSense • ${dateFormat.format(Date())}", MARGIN_L, PAGE_H - MARGIN_BOTTOM + 24f, paintSmall)
            pdfDocument.finishPage(it)
        }
        currentPage = null
        canvas = null
    }

    private fun checkNewPage(needed: Float = LINE_H * 2) {
        if (y + needed > PAGE_H - MARGIN_BOTTOM) startNewPage()
    }

    // ── Cover / Common Header ──────────────────────────────────────────────

    private fun drawCoverHeader(session: SurveySession) {
        val modeLabel = when (session.mode) {
            SurveyMode.SDI     -> "Laporan Survey SDI — Surface Distress Index"
            SurveyMode.PCI     -> "Laporan Survey PCI — Pavement Condition Index"
            SurveyMode.GENERAL -> "Laporan Survey Umum"
        }
        canvas?.drawText("RoadSense", MARGIN_L, y, paintTitle)
        y += 24f
        canvas?.drawText(modeLabel, MARGIN_L, y, paintHeader)
        y += SECTION_GAP * 2

        val rows = mutableListOf(
            "Tanggal"     to dateFormat.format(Date(session.startTime)),
            "Perangkat"   to session.deviceModel
        )
        if (session.surveyorName.isNotBlank()) rows.add("Surveyor" to session.surveyorName)
        if (session.roadName.isNotBlank())    rows.add("Ruas Jalan" to session.roadName)
        if (session.endTime != null) {
            val dur = (session.endTime - session.startTime) / 60000
            rows.add("Durasi" to "$dur menit")
        }
        rows.add("Panjang Total" to formatDistance(session.totalDistance))

        rows.forEach { (label, value) ->
            checkNewPage()
            canvas?.drawText("$label:", MARGIN_L, y, paintSmall)
            canvas?.drawText(value, MARGIN_L + 110f, y, paintBody)
            y += LINE_H
        }
        y += SECTION_GAP
    }

    // ── General Report ────────────────────────────────────────────────────

    private fun drawGeneralReport(
        session: SurveySession,
        conditionDist: Map<String, Double>,
        surfaceDist: Map<String, Double>,
        telemetries: List<TelemetryRaw>
    ) {
        drawSectionHeader("Distribusi Kondisi Jalan")
        conditionDist.forEach { (cond, dist) ->
            checkNewPage()
            val pct = if (session.totalDistance > 0) (dist / session.totalDistance * 100).toInt() else 0
            canvas?.drawText("  $cond: ${formatDistance(dist)} ($pct%)", MARGIN_L, y, paintBody)
            y += LINE_H
        }
        y += SECTION_GAP

        if (surfaceDist.isNotEmpty()) {
            drawSectionHeader("Distribusi Tipe Permukaan")
            surfaceDist.forEach { (surf, dist) ->
                checkNewPage()
                val pct = if (session.totalDistance > 0) (dist / session.totalDistance * 100).toInt() else 0
                canvas?.drawText("  $surf: ${formatDistance(dist)} ($pct%)", MARGIN_L, y, paintBody)
                y += LINE_H
            }
            y += SECTION_GAP
        }

        if (telemetries.isNotEmpty()) {
            drawSectionHeader("Ringkasan Sensor")
            drawTelemetrySummary(telemetries)
        }
    }

    // ── SDI Report ────────────────────────────────────────────────────────

    private fun drawSdiReport(
        session: SurveySession,
        segments: List<SegmentSdi>,
        distressItems: List<DistressItem>,
        telemetries: List<TelemetryRaw>
    ) {
        val avgSdi = if (segments.isNotEmpty()) segments.map { it.sdiScore }.average().toInt() else 0
        val sdiLabel = SDICalculator.categorizeSDI(avgSdi)
        val sdiPaint = Paint(paintHeader).apply { color = sdiColor(avgSdi) }
        canvas?.drawText("Rata-rata SDI: $avgSdi  —  $sdiLabel", MARGIN_L, y, sdiPaint)
        y += 22f
        canvas?.drawText("Rekomendasi: ${SDICalculator().getRecommendation(avgSdi)}", MARGIN_L, y, paintSmall)
        y += SECTION_GAP

        if (telemetries.isNotEmpty()) {
            drawSectionHeader("Ringkasan Sensor")
            drawTelemetrySummary(telemetries)
        }

        drawSectionHeader("Tabel Segmen SDI (${segments.size} segmen × 100m)")
        drawSdiTable(segments, distressItems)
    }

    private fun drawSdiTable(segments: List<SegmentSdi>, distressItems: List<DistressItem>) {
        val colSta   = MARGIN_L
        val colSdi   = MARGIN_L + 130f
        val colCat   = MARGIN_L + 170f
        val colCount = MARGIN_L + 280f

        checkNewPage()
        canvas?.drawText("STA",              colSta,   y, paintSmall)
        canvas?.drawText("SDI",              colSdi,   y, paintSmall)
        canvas?.drawText("Kategori",         colCat,   y, paintSmall)
        canvas?.drawText("Kerusakan",        colCount, y, paintSmall)
        y += LINE_H
        drawDivider()

        segments.forEachIndexed { idx, seg ->
            checkNewPage(LINE_H * 3)
            val rowPaint = Paint(paintBody).apply { color = sdiColor(seg.sdiScore) }
            canvas?.drawText("${seg.startSta}–${seg.endSta}",          colSta,   y, paintBody)
            canvas?.drawText("${seg.sdiScore}",                         colSdi,   y, rowPaint)
            canvas?.drawText(SDICalculator.categorizeSDI(seg.sdiScore), colCat,   y, rowPaint)
            canvas?.drawText("${seg.distressCount}",                    colCount, y, paintBody)
            y += LINE_H

            val segDistress = distressItems.filter { it.segmentId == seg.id }
            if (segDistress.isNotEmpty()) {
                segDistress.take(3).forEach { item ->
                    checkNewPage()
                    canvas?.drawText(
                        "   ↳ ${item.type.name} ${item.severity.name} (${item.lengthOrArea}m)",
                        MARGIN_L, y, paintSmall
                    )
                    y += LINE_H - 4f
                }
                if (segDistress.size > 3) {
                    canvas?.drawText("   ↳ +${segDistress.size - 3} kerusakan lainnya", MARGIN_L, y, paintSmall)
                    y += LINE_H - 4f
                }
            } else if (seg.distressCount == 0) {
                canvas?.drawText("   ↳ Tidak ada kerusakan", MARGIN_L, y, paintSmall)
                y += LINE_H - 4f
            }
        }
        y += SECTION_GAP
    }

    // ── PCI Report — v3: Profesional ASTM D6433 ──────────────────────────

    private fun drawPciReport(
        session: SurveySession,
        segments: List<SegmentPci>,
        distressItems: List<PCIDistressItem>,
        telemetries: List<TelemetryRaw>
    ) {
        val scored  = segments.filter { it.pciScore >= 0 }
        val avgPci  = if (scored.isNotEmpty()) scored.map { it.pciScore }.average().toInt() else -1
        val rating  = if (avgPci >= 0) PCIRating.fromScore(avgPci) else null

        // 1. Executive Summary
        drawSectionHeader("Pavement Condition Index (PCI)")
        if (avgPci >= 0 && rating != null) {
            val pciPaint = Paint(paintHeader).apply { color = Color.parseColor(rating.colorHex) }
            canvas?.drawText("Rata-rata PCI: $avgPci / 100  —  ${rating.displayName}", MARGIN_L, y, pciPaint)
            y += 22f
            canvas?.drawText("Tindakan Penanganan: ${rating.actionRequired}", MARGIN_L, y, paintBody)
            y += LINE_H
            canvas?.drawText("Metodologi: ASTM D6433  |  Segmen: ${segments.size} × 50m", MARGIN_L, y, paintSmall)
        } else {
            canvas?.drawText("Tidak ada data PCI — tidak ada input distress.", MARGIN_L, y, paintBody)
        }
        y += SECTION_GAP

        // 2. Distribusi Rating
        if (scored.isNotEmpty()) {
            drawSectionHeader("Distribusi Kondisi Segmen")
            PCIRating.entries.forEach { r ->
                val count = scored.count { it.pciScore in r.range }
                if (count > 0) {
                    val pct     = (count * 100 / scored.size)
                    val barW    = (contentWidth * count / scored.size.toFloat()).coerceAtLeast(4f)
                    val barPaint = Paint().apply { color = Color.parseColor(r.colorHex) }
                    checkNewPage(LINE_H + 8f)
                    canvas?.drawText("${r.displayName.padEnd(14)} ${count} seg ($pct%)", MARGIN_L, y, paintBody)
                    canvas?.drawRect(RectF(MARGIN_L + 200f, y - 12f, MARGIN_L + 200f + barW, y - 2f), barPaint)
                    y += LINE_H
                }
            }
            y += SECTION_GAP
        }

        // 3. Sensor summary
        if (telemetries.isNotEmpty()) {
            drawSectionHeader("Ringkasan Sensor")
            drawTelemetrySummary(telemetries)
        }

        // 4. Tabel segmen lengkap
        drawSectionHeader("Tabel Segmen PCI (${segments.size} segmen × 50m)")
        drawPciTable(segments, distressItems)

        // 5. Detail distress per segmen (profesional)
        if (distressItems.isNotEmpty()) {
            drawSectionHeader("Detail Kerusakan per Segmen")
            drawPciDistressDetail(segments, distressItems)
        }

        // 6. Rekomendasi
        drawSectionHeader("Rekomendasi Penanganan")
        drawPciRecommendations(segments)
    }

    private fun drawPciTable(segments: List<SegmentPci>, distressItems: List<PCIDistressItem>) {
        val colSta    = MARGIN_L
        val colPci    = MARGIN_L + 120f
        val colRating = MARGIN_L + 160f
        val colCdv    = MARGIN_L + 280f
        val colCount  = MARGIN_L + 330f

        checkNewPage()
        canvas?.drawText("STA",          colSta,    y, paintSmall)
        canvas?.drawText("PCI",          colPci,    y, paintSmall)
        canvas?.drawText("Kondisi",      colRating, y, paintSmall)
        canvas?.drawText("CDV",          colCdv,    y, paintSmall)
        canvas?.drawText("Distress",     colCount,  y, paintSmall)
        y += LINE_H
        drawDivider()

        segments.forEach { seg ->
            checkNewPage(LINE_H * 2)

            if (seg.pciScore >= 0) {
                val r        = PCIRating.fromScore(seg.pciScore)
                val rowPaint = Paint(paintBody).apply { color = Color.parseColor(r.colorHex) }
                canvas?.drawText("${seg.startSta}–${seg.endSta}",                colSta,    y, paintBody)
                canvas?.drawText("${seg.pciScore}",                              colPci,    y, rowPaint)
                canvas?.drawText(r.displayName.take(12),                          colRating, y, rowPaint)
                canvas?.drawText(String.format(Locale.US, "%.1f", seg.correctedDeductValue), colCdv, y, paintSmall)
                canvas?.drawText("${seg.distressCount}",                          colCount,  y, paintBody)
            } else {
                canvas?.drawText("${seg.startSta}–${seg.endSta}",                colSta,    y, paintBody)
                canvas?.drawText("—",                                             colPci,    y, paintSmall)
                canvas?.drawText("Belum disurvey",                               colRating, y, paintSmall)
                canvas?.drawText("—",                                             colCdv,    y, paintSmall)
                canvas?.drawText("0",                                             colCount,  y, paintSmall)
            }
            y += LINE_H

            if (seg.dominantDistressType.isNotBlank() && seg.pciScore >= 0) {
                canvas?.drawText("   ↳ Dominan: ${seg.dominantDistressType}", MARGIN_L, y, paintSmall)
                y += LINE_H - 4f
            }
        }
        y += SECTION_GAP
    }

    private fun drawPciDistressDetail(segments: List<SegmentPci>, allDistress: List<PCIDistressItem>) {
        segments.forEach { seg ->
            val items = allDistress.filter { it.segmentId == seg.id }
            if (items.isEmpty()) return@forEach

            checkNewPage(LINE_H * (items.size + 3).toFloat())
            val r = if (seg.pciScore >= 0) PCIRating.fromScore(seg.pciScore) else null
            val headerPaint = Paint(paintBody).apply {
                color = if (r != null) Color.parseColor(r.colorHex) else Color.DKGRAY
                isFakeBoldText = true
            }
            canvas?.drawText(
                "${seg.startSta}–${seg.endSta}  PCI: ${if (seg.pciScore >= 0) seg.pciScore else "—"}  (${r?.displayName ?: "–"})",
                MARGIN_L, y, headerPaint
            )
            y += LINE_H

            items.forEach { item ->
                checkNewPage()
                canvas?.drawText(
                    "  • ${item.type.displayName} — ${item.severity.name}",
                    MARGIN_L, y, paintBody
                )
                y += LINE_H - 4f
                canvas?.drawText(
                    "    Qty: ${String.format(Locale.US, "%.2f", item.quantity)} ${item.type.unitLabel}" +
                            "  |  Density: ${String.format(Locale.US, "%.2f", item.density)}%" +
                            "  |  DV: ${String.format(Locale.US, "%.1f", item.deductValue)}",
                    MARGIN_L, y, paintSmall
                )
                y += LINE_H
            }
        }
        y += SECTION_GAP
    }

    private fun drawPciRecommendations(segments: List<SegmentPci>) {
        // Kelompokkan segmen per rating
        val byRating = mutableMapOf<PCIRating, MutableList<SegmentPci>>()
        segments.filter { it.pciScore >= 0 }.forEach { seg ->
            val r = PCIRating.fromScore(seg.pciScore)
            byRating.getOrPut(r) { mutableListOf() }.add(seg)
        }

        if (byRating.isEmpty()) {
            canvas?.drawText("Tidak ada data untuk rekomendasi.", MARGIN_L, y, paintBody)
            y += LINE_H
            return
        }

        PCIRating.entries.forEach { r ->
            val segs = byRating[r] ?: return@forEach
            checkNewPage(LINE_H * 3)
            val paint = Paint(paintBody).apply { color = Color.parseColor(r.colorHex); isFakeBoldText = true }
            canvas?.drawText("${r.displayName} (PCI ${r.range.first}–${r.range.last}):", MARGIN_L, y, paint)
            y += LINE_H
            canvas?.drawText("  Tindakan: ${r.actionRequired}", MARGIN_L, y, paintBody)
            y += LINE_H
            canvas?.drawText("  Segmen: ${segs.size} (${segs.sumOf { it.segmentLengthM.toInt() }}m total)", MARGIN_L, y, paintSmall)
            y += LINE_H
        }

        y += SECTION_GAP
        checkNewPage()
        canvas?.drawText("Metodologi: ASTM D6433 Standard Practice for Roads and Parking Lots.", MARGIN_L, y, paintSmall)
        y += LINE_H
        canvas?.drawText("PCI = 100 - max CDV.  CDV = Corrected Deduct Value (iterasi q-value).", MARGIN_L, y, paintSmall)
        y += LINE_H
        canvas?.drawText("Segmen = 50m × lebar lajur.  Rating: Sangat Baik 86-100, ... Gagal 0-10.", MARGIN_L, y, paintSmall)
        y += SECTION_GAP
    }

    // ── Telemetry Summary ─────────────────────────────────────────────────

    private fun drawTelemetrySummary(telemetries: List<TelemetryRaw>) {
        val maxVib    = telemetries.maxOfOrNull { it.vibrationZ } ?: 0f
        val avgVib    = telemetries.map { it.vibrationZ }.average().toFloat()
        val avgSpd    = telemetries.map { it.speed * 3.6f }.average().toFloat()
        val maxSpd    = (telemetries.maxOfOrNull { it.speed } ?: 0f) * 3.6f
        val dataPoints = telemetries.size

        val rows = listOf(
            "Getaran Maks (Z)"   to String.format(Locale.US, "%.3f g", maxVib),
            "Getaran Rata-rata"  to String.format(Locale.US, "%.3f g", avgVib),
            "Kecepatan Rata2"    to String.format(Locale.US, "%.1f km/h", avgSpd),
            "Kecepatan Maks"     to String.format(Locale.US, "%.1f km/h", maxSpd),
            "Titik Data"         to "$dataPoints titik"
        )
        rows.forEach { (label, value) ->
            checkNewPage()
            canvas?.drawText("  $label:", MARGIN_L, y, paintSmall)
            canvas?.drawText(value, MARGIN_L + 130f, y, paintBody)
            y += LINE_H
        }
        y += SECTION_GAP
    }

    // ── Events & Photos ───────────────────────────────────────────────────

    private fun drawEventSection(events: List<RoadEvent>) {
        val photos = events.filter { it.eventType == EventType.PHOTO && it.value.isNotBlank() }
        val voices = events.filter { it.eventType == EventType.VOICE }

        if (photos.isNotEmpty()) {
            drawSectionHeader("Lampiran Foto (${photos.size})")
            drawPhotoGrid(photos)
        }

        if (voices.isNotEmpty()) {
            drawSectionHeader("Rekaman Suara (${voices.size})")
            voices.forEach { event ->
                checkNewPage()
                // FIX W5: STA format yang benar
                canvas?.drawText("🎤  ${formatStaFromDistance(event.distance.toInt())}  —  ${event.notes ?: "Tanpa label"}", MARGIN_L, y, paintBody)
                y += LINE_H
            }
            y += SECTION_GAP
        }
    }

    private fun drawPhotoGrid(photos: List<RoadEvent>) {
        val photosPerRow = 3
        val thumbSpacing = THUMB_W + THUMB_GAP
        var colIdx = 0

        photos.forEach { event ->
            if (colIdx == 0) checkNewPage(THUMB_H + LINE_H * 2)

            val x = MARGIN_L + colIdx * thumbSpacing
            val staText = formatStaFromDistance(event.distance.toInt())  // FIX W5

            try {
                val file = File(event.value)
                if (file.exists()) {
                    // OOM protection: inSampleSize=4
                    val opts   = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(event.value, opts)
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, THUMB_W.toInt(), THUMB_H.toInt(), true)
                        canvas?.drawBitmap(scaled, x, y - THUMB_H, null)
                        bitmap.recycle()
                        scaled.recycle()
                    }
                }
            } catch (e: Exception) {
                val noPhotoPaint = Paint().apply { color = Color.LTGRAY }
                canvas?.drawRect(RectF(x, y - THUMB_H, x + THUMB_W, y), noPhotoPaint)
            }

            canvas?.drawText(staText, x, y + 12f, paintSmall)

            colIdx++
            if (colIdx >= photosPerRow) {
                colIdx = 0
                y += THUMB_H + LINE_H * 2
            }
        }
        if (colIdx > 0) y += THUMB_H + LINE_H * 2
        y += SECTION_GAP
    }

    // ── Drawing Helpers ───────────────────────────────────────────────────

    private fun drawSectionHeader(title: String) {
        checkNewPage(LINE_H * 2)
        val bg = Paint().apply { color = Color.parseColor("#E3F2FD"); style = Paint.Style.FILL }
        canvas?.drawRect(RectF(MARGIN_L - 4f, y - 14f, PAGE_W - MARGIN_R + 4f, y + 4f), bg)
        canvas?.drawText(title, MARGIN_L, y, paintHeader)
        y += LINE_H + 4f
    }

    private fun drawDivider() {
        canvas?.drawLine(MARGIN_L, y, PAGE_W - MARGIN_R, y, paintDivider)
        y += 8f
    }

    // ── Color Helpers ─────────────────────────────────────────────────────

    private fun sdiColor(sdi: Int): Int = when (sdi) {
        in 0..20  -> Color.parseColor("#4CAF50")
        in 21..40 -> Color.parseColor("#8BC34A")
        in 41..60 -> Color.parseColor("#FFC107")
        in 61..80 -> Color.parseColor("#FF9800")
        else      -> Color.parseColor("#F44336")
    }

    private fun pciColor(pci: Int): Int = when (pci) {
        in 86..100 -> Color.parseColor("#4CAF50")
        in 71..85  -> Color.parseColor("#8BC34A")
        in 56..70  -> Color.parseColor("#CDDC39")
        in 41..55  -> Color.parseColor("#FFC107")
        in 26..40  -> Color.parseColor("#FF9800")
        in 11..25  -> Color.parseColor("#F44336")
        else       -> Color.parseColor("#B71C1C")
    }

    // ── String Helpers ────────────────────────────────────────────────────

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    /**
     * FIX W5: Format STA yang benar.
     * Input: jarak dalam meter (Int)
     * 1500m → "STA 1+500"  (bukan "STA 15+0" seperti sebelumnya)
     */
    private fun formatStaFromDistance(meters: Int): String {
        val km = meters / 1000
        val m  = meters % 1000
        return "STA %d+%03d".format(km, m)
    }
}