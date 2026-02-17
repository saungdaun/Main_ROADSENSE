package zaujaani.roadsensebasic.util

import android.content.Context
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileExporter {

    fun exportToGpx(
        context: Context,
        sessionName: String,
        telemetries: List<TelemetryRaw>,
        segments: List<RoadSegment>
    ): File? {
        val fileName = "RoadSense_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.gpx"
        val file = File(context.getExternalFilesDir(null), fileName)

        return try {
            FileWriter(file).use { writer ->
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                writer.write("<gpx version=\"1.1\" creator=\"RoadSense Basic\">\n")
                writer.write("  <metadata>\n")
                writer.write("    <name>${sessionName}</name>\n")
                writer.write("    <time>${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}</time>\n")
                writer.write("  </metadata>\n")

                writer.write("  <trk>\n")
                writer.write("    <name>Track</name>\n")
                writer.write("    <trkseg>\n")

                telemetries.forEach { t ->
                    writer.write("      <trkpt lat=\"${t.latitude}\" lon=\"${t.longitude}\">\n")
                    writer.write("        <ele>${t.altitude}</ele>\n")
                    writer.write("        <time>${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(t.timestamp))}</time>\n")
                    writer.write("        <extensions>\n")
                    writer.write("          <speed>${t.speed}</speed>\n")
                    writer.write("          <vibration>${t.vibrationZ}</vibration>\n")
                    writer.write("          <accuracy>${t.gpsAccuracy}</accuracy>\n")
                    writer.write("          <distance>${t.cumulativeDistance}</distance>\n")
                    writer.write("        </extensions>\n")
                    writer.write("      </trkpt>\n")
                }

                writer.write("    </trkseg>\n")
                writer.write("  </trk>\n")

                segments.forEachIndexed { index, seg ->
                    val midTelemetry = telemetries.find { it.cumulativeDistance >= (seg.startDistance + seg.endDistance) / 2 }
                    midTelemetry?.let {
                        writer.write("  <wpt lat=\"${it.latitude}\" lon=\"${it.longitude}\">\n")
                        writer.write("    <name>Segment ${index+1}</name>\n")
                        writer.write("    <cmt>${seg.name} (${seg.conditionAuto})</cmt>\n")
                        writer.write("  </wpt>\n")
                    }
                }

                writer.write("</gpx>")
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}