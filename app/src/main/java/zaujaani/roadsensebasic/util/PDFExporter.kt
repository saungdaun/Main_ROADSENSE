package zaujaani.roadsensebasic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import zaujaani.roadsensebasic.data.local.entity.*
import zaujaani.roadsensebasic.domain.engine.SDICalculator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PDFExporter {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * Ekspor laporan ke PDF.
     * @param context Context
     * @param session SurveySession
     * @param telemetries List telemetri (opsional, untuk grafik/getaran)
     * @param events List event (foto, voice, dll)
     * @param segmentsSdi List segmen SDI (jika mode SDI)
     * @param distressItems List distress (jika mode SDI)
     * @param conditionDistribution Distribusi kondisi jalan (untuk GENERAL)
     * @param surfaceDistribution Distribusi permukaan
     */
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

        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
            }
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 18f
                isFakeBoldText = true
            }
            val headerPaint = Paint().apply {
                color = Color.BLACK
                textSize = 14f
                isFakeBoldText = true
            }

            var y = 50f

            // Judul
            canvas.drawText("LAPORAN KONDISI JALAN", 50f, y, titlePaint)
            y += 30f

            // Informasi umum
            canvas.drawText("Nama Ruas: ${session.roadName.ifBlank { "-" }}", 50f, y, paint)
            y += 20f
            canvas.drawText("Tanggal: ${dateFormat.format(Date(session.startTime))}", 50f, y, paint)
            y += 20f
            canvas.drawText("Surveyor: ${session.surveyorName.ifBlank { "-" }}", 50f, y, paint)
            y += 20f
            canvas.drawText("Mode: ${session.mode.name}", 50f, y, paint)
            y += 20f
            canvas.drawText("Total Panjang: ${String.format(Locale.US, "%.2f km", session.totalDistance / 1000)}", 50f, y, paint)
            y += 30f

            if (session.mode == SurveyMode.GENERAL) {
                // GENERAL: tampilkan distribusi kondisi dan permukaan
                canvas.drawText("Distribusi Kondisi Jalan:", 50f, y, headerPaint)
                y += 25f
                conditionDistribution.forEach { (cond, length) ->
                    val pct = if (session.totalDistance > 0) (length / session.totalDistance * 100).toInt() else 0
                    val text = "  $cond: ${length.toInt()} m ($pct%)"
                    canvas.drawText(text, 70f, y, paint)
                    y += 18f
                }
                y += 10f

                canvas.drawText("Distribusi Permukaan:", 50f, y, headerPaint)
                y += 25f
                surfaceDistribution.forEach { (surface, length) ->
                    val pct = if (session.totalDistance > 0) (length / session.totalDistance * 100).toInt() else 0
                    val text = "  $surface: ${length.toInt()} m ($pct%)"
                    canvas.drawText(text, 70f, y, paint)
                    y += 18f
                }
            } else {
                // SDI: tampilkan ringkasan SDI
                val avgSdi = if (segmentsSdi.isNotEmpty()) segmentsSdi.map { it.sdiScore }.average().toInt() else 0
                canvas.drawText("Rata-rata SDI: $avgSdi (${SDICalculator.categorizeSDI(avgSdi)})", 50f, y, paint)
                y += 30f

                // Tabel segmen
                canvas.drawText("Daftar Segmen:", 50f, y, headerPaint)
                y += 25f

                segmentsSdi.forEachIndexed { index, seg ->
                    val text = "${index + 1}. ${seg.startSta} – ${seg.endSta}  SDI: ${seg.sdiScore} (${SDICalculator.categorizeSDI(seg.sdiScore)})  Kerusakan: ${seg.distressCount}"
                    canvas.drawText(text, 70f, y, paint)
                    y += 18f

                    val segDistress = distressItems.filter { it.segmentId == seg.id }
                    segDistress.forEach { item ->
                        val line = "    - ${item.type.name} (${item.severity.name}) ${item.lengthOrArea} m/m² STA ${item.sta}"
                        canvas.drawText(line, 90f, y, paint)
                        y += 18f
                        if (item.photoPath.isNotBlank()) {
                            canvas.drawText("       📷 Foto tersedia", 110f, y, paint)
                            y += 18f
                        }
                    }
                    y += 5f
                }
            }

            // Daftar foto/event penting
            if (events.isNotEmpty()) {
                canvas.drawText("Dokumentasi:", 50f, y, headerPaint)
                y += 25f
                events.filter { it.eventType == EventType.PHOTO || it.eventType == EventType.VOICE }
                    .forEach { event ->
                        val type = if (event.eventType == EventType.PHOTO) "📷 Foto" else "🎤 Rekaman"
                        val sta = event.distance.toInt()
                        val staStr = "STA ${sta/100}+${sta%100}"
                        val line = "  $type - $staStr"
                        canvas.drawText(line, 70f, y, paint)
                        y += 18f
                        if (!event.notes.isNullOrBlank()) {
                            canvas.drawText("     ${event.notes}", 90f, y, paint)
                            y += 18f
                        }
                    }
            }

            pdfDocument.finishPage(page)
            FileOutputStream(file).use { out -> pdfDocument.writeTo(out) }
            pdfDocument.close()
            return file
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }
}