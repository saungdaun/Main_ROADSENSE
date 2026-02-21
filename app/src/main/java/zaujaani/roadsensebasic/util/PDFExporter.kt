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

/**
 * PDFExporter v2 — Multi-page, foto thumbnail, ringkasan telemetri
 *
 * Perubahan dari v1:
 * - Multi-page: otomatis buat halaman baru saat konten meluap
 * - Foto thumbnail di laporan (max 80×60px per foto)
 * - Ringkasan telemetri (maks vibrationZ, rata-rata kecepatan) di laporan SDI
 * - Header & footer per halaman (nama proyek + nomor halaman)
 * - Warna indikator SDI per baris segmen
 */
object PDFExporter {

    // ── Layout constants ──────────────────────────────────────────────────
    private const val PAGE_W       = 595f   // A4 portrait
    private const val PAGE_H       = 842f
    private const val MARGIN_L     = 50f
    private const val MARGIN_R     = 50f
    private const val MARGIN_TOP   = 60f
    private const val MARGIN_BOTTOM = 60f
    private const val LINE_H       = 18f
    private const val SECTION_GAP  = 14f
    private const val THUMB_W      = 80f
    private const val THUMB_H      = 60f
    private const val THUMB_GAP    = 8f

    private val contentWidth get() = PAGE_W - MARGIN_L - MARGIN_R

    private val dateFormat     = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // ── Paint definitions ─────────────────────────────────────────────────
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

    // ── State per dokumen ─────────────────────────────────────────────────
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
        telemetries: List<TelemetryRaw> = emptyList(),
        events: List<RoadEvent> = emptyList(),
        segmentsSdi: List<SegmentSdi> = emptyList(),
        distressItems: List<DistressItem> = emptyList(),
        conditionDistribution: Map<String, Double> = emptyMap(),
        surfaceDistribution: Map<String, Double> = emptyMap()
    ): File? {
        val fileName = "RoadSense_${fileNameFormat.format(Date())}.pdf"
        val dir = context.getExternalFilesDir("Reports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        return try {
            pdfDocument = PdfDocument()
            sessionTitle = session.roadName.ifBlank { "RoadSense Survey" }
            newPage()

            drawTitle(session)
            drawInfoBlock(session)
            drawDivider()

            if (session.mode == SurveyMode.GENERAL) {
                drawGeneralReport(session, conditionDistribution, surfaceDistribution, telemetries)
            } else {
                drawSdiReport(session, segmentsSdi, distressItems, telemetries)
            }

            drawEventSection(events)

            finishCurrentPage()
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
            pdfDocument.close()
            file
        } catch (e: IOException) {
            e.printStackTrace()
            pdfDocument.close()
            null
        }
    }

    // ── Page management ───────────────────────────────────────────────────

    private fun newPage() {
        finishCurrentPage()
        pageNum++
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNum).create()
        currentPage = pdfDocument.startPage(pageInfo)
        canvas = currentPage!!.canvas
        y = MARGIN_TOP
        drawPageHeader()
    }

    private fun finishCurrentPage() {
        currentPage?.let { pdfDocument.finishPage(it) }
        currentPage = null
        canvas = null
    }

    private fun checkNewPage(neededHeight: Float = LINE_H * 3) {
        if (y + neededHeight > PAGE_H - MARGIN_BOTTOM) newPage()
    }

    // ── Per-page header & footer ──────────────────────────────────────────

    private fun drawPageHeader() {
        val c = canvas ?: return
        c.drawText("RoadSense  |  $sessionTitle", MARGIN_L, 30f, paintSmall)
        c.drawText("Hal. $pageNum", PAGE_W - MARGIN_R - 30f, 30f, paintSmall)
        c.drawLine(MARGIN_L, 35f, PAGE_W - MARGIN_R, 35f, paintDivider)
    }

    // ── Section: Title ────────────────────────────────────────────────────

    private fun drawTitle(session: SurveySession) {
        val c = canvas ?: return
        c.drawText("LAPORAN KONDISI JALAN", MARGIN_L, y, paintTitle)
        y += 28f
        val modeLabel = if (session.mode == SurveyMode.GENERAL) "Survey Umum" else "Survey SDI"
        c.drawText(modeLabel, MARGIN_L, y, paintHeader)
        y += 24f
    }

    private fun drawInfoBlock(session: SurveySession) {
        val c = canvas ?: return
        val rows = listOf(
            "Nama Ruas"   to session.roadName.ifBlank { "-" },
            "Surveyor"    to session.surveyorName.ifBlank { "-" },
            "Tanggal"     to dateFormat.format(Date(session.startTime)),
            "Panjang"     to String.format(Locale.US, "%.1f m (%.3f km)",
                session.totalDistance, session.totalDistance / 1000)
        )
        rows.forEach { (label, value) ->
            checkNewPage()
            c.drawText("$label:", MARGIN_L, y, paintSmall)
            c.drawText(value, MARGIN_L + 90f, y, paintBody)
            y += LINE_H
        }
        y += SECTION_GAP
    }

    // ── Section: General ──────────────────────────────────────────────────

    private fun drawGeneralReport(
        session: SurveySession,
        conditionDist: Map<String, Double>,
        surfaceDist: Map<String, Double>,
        telemetries: List<TelemetryRaw>
    ) {
        drawSectionHeader("Distribusi Kondisi Jalan")
        drawDistributionTable(conditionDist, session.totalDistance, conditionColor = true)

        drawSectionHeader("Distribusi Permukaan")
        drawDistributionTable(surfaceDist, session.totalDistance, conditionColor = false)

        if (telemetries.isNotEmpty()) {
            drawSectionHeader("Ringkasan Sensor")
            drawTelemetrySummary(telemetries)
        }
    }

    private fun drawDistributionTable(
        distribution: Map<String, Double>,
        total: Double,
        conditionColor: Boolean
    ) {
        if (distribution.isEmpty()) {
            checkNewPage()
            canvas?.drawText("  Tidak ada data.", MARGIN_L, y, paintSmall)
            y += LINE_H + SECTION_GAP
            return
        }
        distribution.forEach { (label, length) ->
            checkNewPage()
            val pct = if (total > 0) (length / total * 100).toInt() else 0
            val bar = buildBar(pct, 30)
            val color = if (conditionColor) conditionBarColor(label) else Color.parseColor("#1976D2")
            val barPaint = Paint(paintBody).apply { this.color = color }
            canvas?.drawText("  $label", MARGIN_L, y, paintBody)
            canvas?.drawText(bar, MARGIN_L + 100f, y, barPaint)
            canvas?.drawText("${length.toInt()} m  ($pct%)", MARGIN_L + 270f, y, paintSmall)
            y += LINE_H
        }
        y += SECTION_GAP
    }

    private fun buildBar(pct: Int, width: Int): String {
        val filled = (pct * width / 100).coerceIn(0, width)
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    private fun conditionBarColor(label: String): Int = when {
        label.contains("Baik Sekali", true) || label == "Baik" -> Color.parseColor("#4CAF50")
        label.contains("Sedang", true)                         -> Color.parseColor("#FFC107")
        label.contains("Rusak Ringan", true)                   -> Color.parseColor("#FF9800")
        label.contains("Rusak Berat", true)                    -> Color.parseColor("#F44336")
        else                                                    -> Color.GRAY
    }

    // ── Section: SDI ─────────────────────────────────────────────────────

    private fun drawSdiReport(
        session: SurveySession,
        segments: List<SegmentSdi>,
        distressItems: List<DistressItem>,
        telemetries: List<TelemetryRaw>
    ) {
        val avgSdi = if (segments.isNotEmpty()) segments.map { it.sdiScore }.average().toInt() else 0
        checkNewPage()
        val sdiLabel = SDICalculator.categorizeSDI(avgSdi)
        val sdiPaint = Paint(paintHeader).apply { color = sdiColor(avgSdi) }
        canvas?.drawText("Rata-rata SDI: $avgSdi  —  $sdiLabel", MARGIN_L, y, sdiPaint)
        y += 22f

        if (telemetries.isNotEmpty()) {
            drawSectionHeader("Ringkasan Sensor")
            drawTelemetrySummary(telemetries)
        }

        drawSectionHeader("Tabel Segmen SDI (${segments.size} segmen × 100m)")
        drawSdiTable(segments, distressItems)
    }

    private fun drawSdiTable(segments: List<SegmentSdi>, distressItems: List<DistressItem>) {
        // Kolom header
        checkNewPage(LINE_H * 2)
        val c = canvas ?: return
        val colSta   = MARGIN_L
        val colSdi   = MARGIN_L + 130f
        val colCat   = MARGIN_L + 185f
        val colCount = MARGIN_L + 300f

        c.drawText("STA Awal – Akhir", colSta,   y, paintSmall)
        c.drawText("SDI",              colSdi,   y, paintSmall)
        c.drawText("Kategori",         colCat,   y, paintSmall)
        c.drawText("Kerusakan",        colCount, y, paintSmall)
        y += LINE_H
        c.drawLine(MARGIN_L, y - 4f, PAGE_W - MARGIN_R, y - 4f, paintDivider)

        segments.forEachIndexed { idx, seg ->
            checkNewPage(LINE_H + 10f)
            val rowPaint = Paint(paintBody).apply { color = sdiColor(seg.sdiScore) }
            canvas?.drawText("${seg.startSta} – ${seg.endSta}", colSta,   y, paintBody)
            canvas?.drawText("${seg.sdiScore}",                 colSdi,   y, rowPaint)
            canvas?.drawText(SDICalculator.categorizeSDI(seg.sdiScore), colCat, y, rowPaint)
            canvas?.drawText("${seg.distressCount}",            colCount, y, paintBody)
            y += LINE_H

            // Detail kerusakan per segmen
            val segDistress = distressItems.filter { it.segmentId == seg.id }
            segDistress.forEach { item ->
                checkNewPage()
                val detail = "    • ${item.type.displayName} (${item.severity.displayName})" +
                        "  ${item.lengthOrArea} ${item.type.unit}  STA ${item.sta}"
                canvas?.drawText(detail, MARGIN_L + 10f, y, paintSmall)
                y += LINE_H - 2f

                // Thumbnail foto jika ada
                if (item.photoPath.isNotBlank()) {
                    drawPhotoThumbnail(item.photoPath, "STA ${item.sta}")
                }
            }
            if (segDistress.isEmpty() && seg.distressCount == 0) {
                checkNewPage()
                canvas?.drawText("    Tidak ada kerusakan tercatat", MARGIN_L + 10f, y, paintSmall)
                y += LINE_H - 2f
            }
            y += 4f
        }
        y += SECTION_GAP
    }

    // ── Section: Telemetri summary ─────────────────────────────────────────

    private fun drawTelemetrySummary(telemetries: List<TelemetryRaw>) {
        val maxVib  = telemetries.maxOfOrNull { it.vibrationZ } ?: 0f
        val avgVib  = telemetries.map { it.vibrationZ }.average().toFloat()
        val avgSpd  = telemetries.map { it.speed * 3.6f }.average().toFloat() // m/s → km/h
        val maxSpd  = (telemetries.maxOfOrNull { it.speed } ?: 0f) * 3.6f
        val dataPoints = telemetries.size

        val rows = listOf(
            "Getaran Maks (Z)"  to String.format(Locale.US, "%.3f g", maxVib),
            "Getaran Rata-rata" to String.format(Locale.US, "%.3f g", avgVib),
            "Kecepatan Rata2"   to String.format(Locale.US, "%.1f km/h", avgSpd),
            "Kecepatan Maks"    to String.format(Locale.US, "%.1f km/h", maxSpd),
            "Titik Data"        to "$dataPoints titik"
        )
        rows.forEach { (label, value) ->
            checkNewPage()
            canvas?.drawText("  $label:", MARGIN_L, y, paintSmall)
            canvas?.drawText(value, MARGIN_L + 130f, y, paintBody)
            y += LINE_H
        }
        y += SECTION_GAP
    }

    // ── Section: Events / Foto ────────────────────────────────────────────

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
                val sta = event.distance.toInt()
                val staStr = "STA ${sta / 1000}+${String.format("%03d", sta % 1000)}"
                canvas?.drawText("🎤  $staStr  —  ${event.notes ?: "Tanpa label"}", MARGIN_L, y, paintBody)
                y += LINE_H
            }
            y += SECTION_GAP
        }
    }

    private fun drawPhotoGrid(photos: List<RoadEvent>) {
        // Tampilkan 3 foto per baris
        val cols       = 3
        val cellWidth  = (contentWidth - (cols - 1) * THUMB_GAP) / cols
        val cellHeight = THUMB_H + LINE_H + 6f

        var col = 0
        var rowY = y

        photos.forEach { event ->
            if (col == 0) {
                checkNewPage(cellHeight + 10f)
                rowY = y
            }
            val x = MARGIN_L + col * (cellWidth + THUMB_GAP)
            drawPhotoThumbnailAt(event.value, x, rowY, cellWidth, THUMB_H)

            val sta = event.distance.toInt()
            val staStr = "STA ${sta / 1000}+${String.format("%03d", sta % 1000)}"
            canvas?.drawText(staStr, x, rowY + THUMB_H + LINE_H, paintSmall)

            col++
            if (col >= cols) {
                col = 0
                y = rowY + cellHeight
            }
        }
        if (col > 0) y = rowY + cellHeight
        y += SECTION_GAP
    }

    private fun drawPhotoThumbnail(photoPath: String, caption: String) {
        checkNewPage(THUMB_H + LINE_H + 4f)
        drawPhotoThumbnailAt(photoPath, MARGIN_L + 20f, y, THUMB_W, THUMB_H)
        canvas?.drawText(caption, MARGIN_L + THUMB_W + 30f, y + THUMB_H / 2, paintSmall)
        y += THUMB_H + 6f
    }

    private fun drawPhotoThumbnailAt(path: String, x: Float, yPos: Float, w: Float, h: Float) {
        val c = canvas ?: return
        try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, this)
                inSampleSize = calculateInSampleSize(outWidth, outHeight, w.toInt(), h.toInt())
                inJustDecodeBounds = false
            }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: run {
                drawPlaceholder(c, x, yPos, w, h, "Foto\ntidak\nditemukan")
                return
            }
            val scaled = Bitmap.createScaledBitmap(bmp, w.toInt(), h.toInt(), true)
            c.drawBitmap(scaled, x, yPos, null)
            val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
            c.drawRect(RectF(x, yPos, x + w, yPos + h), border)
            scaled.recycle()
            bmp.recycle()
        } catch (e: Exception) {
            drawPlaceholder(c, x, yPos, w, h, "Error")
        }
    }

    private fun drawPlaceholder(c: Canvas, x: Float, y: Float, w: Float, h: Float, text: String) {
        val bg = Paint().apply { color = Color.parseColor("#EEEEEE") }
        c.drawRect(RectF(x, y, x + w, y + h), bg)
        c.drawText(text, x + 4f, y + h / 2f, paintSmall)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun drawSectionHeader(title: String) {
        checkNewPage(LINE_H * 2)
        val c = canvas ?: return
        c.drawText(title, MARGIN_L, y, paintHeader)
        y += 4f
        c.drawLine(MARGIN_L, y, PAGE_W - MARGIN_R, y, paintDivider)
        y += LINE_H
    }

    private fun drawDivider() {
        canvas?.drawLine(MARGIN_L, y, PAGE_W - MARGIN_R, y, paintDivider)
        y += SECTION_GAP
    }

    private fun sdiColor(sdi: Int): Int = when (sdi) {
        in 0..20  -> Color.parseColor("#4CAF50")
        in 21..40 -> Color.parseColor("#8BC34A")
        in 41..60 -> Color.parseColor("#FFC107")
        in 61..80 -> Color.parseColor("#FF9800")
        else      -> Color.parseColor("#F44336")
    }
}