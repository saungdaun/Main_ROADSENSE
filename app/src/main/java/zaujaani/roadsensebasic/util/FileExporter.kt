package zaujaani.roadsensebasic.util

import android.content.Context
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
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

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    private fun getFilenameDateFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    /**
     * Ekspor telemetri dan event ke format GPX.
     * Menyertakan kecepatan, getaran (X,Y,Z), akurasi GPS, dan waypoint untuk event penting.
     */
    fun exportToGpx(
        context: Context,
        sessionName: String,
        telemetries: List<TelemetryRaw>,
        events: List<RoadEvent>
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
                    w.write("          <condition>${t.condition.name}</condition>\n")
                    w.write("          <surface>${t.surface.name}</surface>\n")
                    w.write("        </extensions>\n")
                    w.write("      </trkpt>\n")
                }
                w.write("    </trkseg>\n")
                w.write("  </trk>\n")

                // Waypoints untuk event penting
                events.forEach { event ->
                    w.write("  <wpt lat=\"${event.latitude}\" lon=\"${event.longitude}\">\n")
                    w.write("    <time>${isoFormatter.format(Instant.ofEpochMilli(event.timestamp))}</time>\n")
                    val name = when (event.eventType) {
                        EventType.CONDITION_CHANGE -> "Kondisi: ${event.value}"
                        EventType.SURFACE_CHANGE -> "Permukaan: ${event.value}"
                        EventType.PHOTO -> "Foto"
                        EventType.VOICE -> "Rekaman"
                    }
                    w.write("    <name>$name</name>\n")
                    w.write("    <cmt>${event.notes ?: ""}</cmt>\n")
                    w.write("    <extensions>\n")
                    w.write("      <distance>${event.distance}</distance>\n")
                    w.write("      <event_type>${event.eventType}</event_type>\n")
                    w.write("    </extensions>\n")
                    w.write("  </wpt>\n")
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
     * Ekspor session + telemetri + event ke format CSV untuk analisis spreadsheet.
     * Berguna untuk konsultan perencanaan dan dinas PU.
     */
    fun exportToCsv(
        context: Context,
        sessionName: String,
        session: SurveySession,
        telemetries: List<TelemetryRaw>,
        events: List<RoadEvent>
    ): File? {
        val fileName = "RoadSense_${getFilenameDateFormat().format(Date())}.csv"
        val dir = context.getExternalFilesDir("Reports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        val csvDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        return try {
            FileWriter(file).use { w ->
                // Header session
                w.write("# LAPORAN SURVEY KONDISI JALAN - ROADSENSE\n")
                w.write("# Tanggal Ekspor,${csvDateFormat.format(Date())}\n")
                w.write("# Nama Sesi,${sessionName}\n")
                w.write("# Surveyor,${session.surveyorName}\n")
                w.write("# Nama Jalan,${session.roadName}\n")
                w.write("# Perangkat,${session.deviceModel}\n")
                w.write("# Total Jarak,${session.totalDistance.toInt()} m\n")
                w.write("# Jumlah Titik Telemetri,${telemetries.size}\n")
                w.write("# Jumlah Event,${events.size}\n")
                w.write("\n")

                // Ringkasan kondisi dan permukaan dari telemetri
                if (telemetries.isNotEmpty()) {
                    val conditionDist = mutableMapOf<String, Double>()
                    val surfaceDist = mutableMapOf<String, Double>()
                    var lastDist = telemetries.first().cumulativeDistance
                    var lastCondition = telemetries.first().condition.name
                    var lastSurface = telemetries.first().surface.name

                    for (i in 1 until telemetries.size) {
                        val t = telemetries[i]
                        val delta = t.cumulativeDistance - lastDist
                        if (delta > 0) {
                            conditionDist[lastCondition] = conditionDist.getOrDefault(lastCondition, 0.0) + delta
                            surfaceDist[lastSurface] = surfaceDist.getOrDefault(lastSurface, 0.0) + delta
                        }
                        lastDist = t.cumulativeDistance
                        lastCondition = t.condition.name
                        lastSurface = t.surface.name
                    }

                    w.write("# RINGKASAN KONDISI JALAN\n")
                    conditionDist.forEach { (cond, len) ->
                        val pct = if (session.totalDistance > 0) (len / session.totalDistance * 100) else 0.0
                        w.write("# $cond,${len.toInt()} m,${String.format(Locale.US, "%.1f", pct)}%\n")
                    }
                    w.write("\n")

                    w.write("# RINGKASAN JENIS PERMUKAAN\n")
                    surfaceDist.forEach { (surf, len) ->
                        val pct = if (session.totalDistance > 0) (len / session.totalDistance * 100) else 0.0
                        w.write("# $surf,${len.toInt()} m,${String.format(Locale.US, "%.1f", pct)}%\n")
                    }
                    w.write("\n")
                }

                // Header kolom telemetri
                w.write("Timestamp,Latitude,Longitude,Altitude,Speed (m/s),GPS Accuracy (m),")
                w.write("Vibration X,Vibration Y,Vibration Z,Cumulative Distance (m),")
                w.write("Condition,Surface\n")

                // Data telemetri
                telemetries.forEach { t ->
                    w.write("\"${csvDateFormat.format(Date.from(t.timestamp))}\",")
                    w.write("${t.latitude},${t.longitude},${t.altitude},${t.speed},")
                    w.write("${t.gpsAccuracy},${t.vibrationX},${t.vibrationY},${t.vibrationZ},")
                    w.write("${t.cumulativeDistance},\"${t.condition.name}\",\"${t.surface.name}\"\n")
                }

                w.write("\n")
                w.write("# DAFTAR EVENT\n")
                w.write("Timestamp,Jarak (m),Jenis Event,Nilai,Catatan\n")
                events.forEach { e ->
                    w.write("\"${csvDateFormat.format(Date(e.timestamp))}\",")
                    w.write("${e.distance},\"${e.eventType}\",\"${e.value}\",\"${e.notes ?: ""}\"\n")
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}