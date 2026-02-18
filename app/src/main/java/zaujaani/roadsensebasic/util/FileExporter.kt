package zaujaani.roadsensebasic.util

import android.content.Context
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Utility untuk ekspor data survey ke berbagai format.
 * - GPX: format standar tracking GPS untuk GIS tools
 * - CSV: untuk analisis spreadsheet konsultan/dinas PU
 */
object FileExporter {

    // Gunakan DateTimeFormatter untuk ISO 8601 (thread-safe dan modern)
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    // Untuk nama file, kita buat fungsi agar tidak menyimpan SimpleDateFormat statis
    private fun getFilenameDateFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    /**
     * Ekspor telemetri dan segmen ke format GPX.
     * Menyertakan kecepatan, getaran (X,Y,Z), akurasi GPS, dan waypoint per segmen.
     */
    fun exportToGpx(
        context: Context,
        sessionName: String,
        telemetries: List<TelemetryRaw>,
        segments: List<RoadSegment>
    ): File? {
        val fileName = "RoadSense_${getFilenameDateFormat().format(Date())}.gpx"
        val dir = context.getExternalFilesDir("Reports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        return try {
            FileWriter(file).use { w ->
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                w.write("<gpx version=\"1.1\" creator=\"RoadSense\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                w.write("  <metadata>\n")
                w.write("    <name>$sessionName</name>\n")
                w.write("    <time>${isoFormatter.format(Instant.now())}</time>\n")
                w.write("  </metadata>\n")

                // Track
                w.write("  <trk>\n")
                w.write("    <name>Road Survey Track</name>\n")
                w.write("    <trkseg>\n")
                telemetries.forEach { t ->
                    w.write("      <trkpt lat=\"${t.latitude}\" lon=\"${t.longitude}\">\n")
                    w.write("        <ele>${t.altitude}</ele>\n")
                    w.write("        <time>${isoFormatter.format(t.timestamp)}</time>\n")
                    w.write("        <extensions>\n")
                    w.write("          <speed>${t.speed}</speed>\n")
                    w.write("          <vibration_x>${t.vibrationX}</vibration_x>\n")
                    w.write("          <vibration_y>${t.vibrationY}</vibration_y>\n")
                    w.write("          <vibration_z>${t.vibrationZ}</vibration_z>\n")
                    w.write("          <gps_accuracy>${t.gpsAccuracy}</gps_accuracy>\n")
                    w.write("          <cumulative_distance>${t.cumulativeDistance}</cumulative_distance>\n")
                    w.write("        </extensions>\n")
                    w.write("      </trkpt>\n")
                }
                w.write("    </trkseg>\n")
                w.write("  </trk>\n")

                // Waypoints per segmen
                segments.forEachIndexed { index, seg ->
                    if (seg.startLat != 0.0 || seg.startLng != 0.0) {
                        w.write("  <wpt lat=\"${seg.startLat}\" lon=\"${seg.startLng}\">\n")
                        w.write("    <name>Seg ${index+1} Start: ${seg.name}</name>\n")
                        w.write("    <cmt>${seg.conditionAuto} | ${seg.surfaceType} | ${(seg.endDistance-seg.startDistance).toInt()}m | Conf:${seg.confidence}%</cmt>\n")
                        w.write("  </wpt>\n")
                    }
                    if (seg.endLat != 0.0 || seg.endLng != 0.0) {
                        w.write("  <wpt lat=\"${seg.endLat}\" lon=\"${seg.endLng}\">\n")
                        w.write("    <name>Seg ${index+1} End: ${seg.name}</name>\n")
                        w.write("    <cmt>${seg.conditionAuto}</cmt>\n")
                        w.write("  </wpt>\n")
                    }
                }

                w.write("</gpx>")
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Ekspor session + segmen ke format CSV untuk analisis spreadsheet.
     * Berguna untuk konsultan perencanaan dan dinas PU.
     */
    fun exportToCsv(
        context: Context,
        sessionName: String,
        session: SurveySession,
        segments: List<RoadSegment>
    ): File? {
        val fileName = "RoadSense_${getFilenameDateFormat().format(Date())}.csv"
        val dir = context.getExternalFilesDir("Reports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        // Buat formatter CSV di dalam fungsi (tidak disimpan statis)
        val csvDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        return try {
            FileWriter(file).use { w ->
                // Header session
                w.write("# LAPORAN SURVEY KONDISI JALAN - ROADSENSE\n")
                w.write("# Tanggal Ekspor,${csvDateFormat.format(Date())}\n")
                w.write("# Surveyor,${session.surveyorName}\n")
                w.write("# Nama Jalan,${session.roadName}\n")
                w.write("# Perangkat,${session.deviceModel}\n")
                w.write("# Total Jarak,${session.totalDistance.toInt()} m\n")
                w.write("# Jumlah Segmen,${segments.size}\n")
                w.write("\n")

                // Ringkasan kondisi
                w.write("# RINGKASAN KONDISI JALAN\n")
                val conditionDist = mutableMapOf<String, Double>()
                segments.forEach { seg ->
                    val len = seg.endDistance - seg.startDistance
                    conditionDist[seg.conditionAuto] = (conditionDist[seg.conditionAuto] ?: 0.0) + len
                }
                conditionDist.forEach { (cond, len) ->
                    val pct = if (session.totalDistance > 0) (len / session.totalDistance * 100) else 0.0
                    w.write("# $cond,${len.toInt()} m,${String.format(Locale.US, "%.1f", pct)}%\n")
                }
                w.write("\n")

                // Header kolom segmen
                w.write("No,Nama Ruas,Kondisi,Permukaan,Awal (m),Akhir (m),Panjang (m),")
                w.write("Getaran Avg,Confidence (%),Lat Awal,Lng Awal,Lat Akhir,Lng Akhir,")
                w.write("Foto,Audio,Catatan\n")

                // Data segmen
                segments.forEachIndexed { i, seg ->
                    val len = (seg.endDistance - seg.startDistance).toInt()
                    w.write("${i+1},")
                    w.write("\"${seg.name}\",")
                    w.write("\"${seg.conditionAuto}\",")
                    w.write("\"${seg.surfaceType}\",")
                    w.write("${seg.startDistance.toInt()},")
                    w.write("${seg.endDistance.toInt()},")
                    w.write("$len,")
                    w.write("${String.format(Locale.US, "%.3f", seg.avgVibration)},")
                    w.write("${seg.confidence},")
                    w.write("${seg.startLat},")
                    w.write("${seg.startLng},")
                    w.write("${seg.endLat},")
                    w.write("${seg.endLng},")
                    w.write("${if (seg.photoPath.isNotBlank()) "Ya" else "Tidak"},")
                    w.write("${if (seg.audioPath.isNotBlank()) "Ya" else "Tidak"},")
                    w.write("\"${seg.notes}\"\n")
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}