package zaujaani.roadsensebasic.util

import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

class MapRenderer(private val mapView: MapView) {

    private companion object {
        const val MIN_DISTANCE_METERS = 2.0
        const val MAX_TOTAL_POINTS = 8000
        const val POLYLINE_WIDTH = 9f
        const val ZOOM_CHANGE_THRESHOLD = 0.5
        const val BEARING_SMOOTH_FACTOR = 0.15f
    }

    // ── Segment Model ──────────────────────────────────────────────────────────

    private data class ColoredSegment(
        val polyline: Polyline,
        val shadow: Polyline? = null,
        val points: MutableList<GeoPoint> = mutableListOf()
    )

    private val segments = mutableListOf<ColoredSegment>()
    private var currentColor: Int = -1
    private var lastAddedPoint: GeoPoint? = null
    private var totalPointCount = 0

    // ── Marker & Accuracy ──────────────────────────────────────────────────────

    private var myLocationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    // ── Camera State ───────────────────────────────────────────────────────────

    private var lastCameraPoint: GeoPoint? = null
    private var lastZoomLevel: Double = 18.0
    private var lastBearing = 0f
    private var isOrientationEnabled = true

    // ── Initialization ─────────────────────────────────────────────────────────

    fun initPolyline(defaultColor: Int) {
        startNewSegment(defaultColor)
    }

    private fun startNewSegment(color: Int): ColoredSegment {
        // Layer bawah: shadow hitam tipis untuk kontras di peta terang
        val shadowPolyline = Polyline().apply {
            outlinePaint.apply {
                this.color = 0x55000000.toInt()
                strokeWidth = POLYLINE_WIDTH + 3f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
        }

        // Layer atas: warna kondisi jalan
        val polyline = Polyline().apply {
            outlinePaint.apply {
                this.color = color
                strokeWidth = POLYLINE_WIDTH
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
        }

        mapView.overlays.add(shadowPolyline)
        mapView.overlays.add(polyline)

        val segment = ColoredSegment(polyline, shadowPolyline)
        segments.add(segment)
        currentColor = color
        return segment
    }

    // ── Polyline Drawing ───────────────────────────────────────────────────────

    fun addGeoPoint(geoPoint: GeoPoint, conditionColor: Int) {
        // Filter titik terlalu dekat
        lastAddedPoint?.let { last ->
            if (last.distanceToAsDouble(geoPoint) < MIN_DISTANCE_METERS) return
        }

        // Mulai segmen baru jika warna berubah
        if (conditionColor != currentColor) {
            // Bridge point: sambung segmen baru dari titik akhir sebelumnya
            // agar tidak ada gap di polyline
            val bridgePoint = lastAddedPoint
            val newSegment = startNewSegment(conditionColor)
            bridgePoint?.let {
                newSegment.points.add(it)
                newSegment.polyline.setPoints(newSegment.points.toList())
                newSegment.shadow?.setPoints(newSegment.points.toList())
            }
        }

        val currentSegment = segments.last()
        currentSegment.points.add(geoPoint)
        currentSegment.polyline.setPoints(currentSegment.points.toList())
        currentSegment.shadow?.setPoints(currentSegment.points.toList())

        lastAddedPoint = geoPoint
        totalPointCount++

        // Pruning: hapus segmen paling lama jika total titik terlalu banyak
        if (totalPointCount > MAX_TOTAL_POINTS) {
            pruneOldestSegment()
        }

        mapView.invalidate()
    }

    private fun pruneOldestSegment() {
        if (segments.size <= 1) return
        val oldest = segments.removeAt(0)
        totalPointCount -= oldest.points.size
        mapView.overlays.remove(oldest.polyline)
        oldest.shadow?.let { mapView.overlays.remove(it) }
    }

    // ── My Location ────────────────────────────────────────────────────────────

    fun updateMyLocation(location: Location, myLocationIcon: Drawable?) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        if (myLocationMarker == null) {
            myLocationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = myLocationIcon
                isFlat = true
            }
        }

        myLocationMarker!!.apply {
            position = geoPoint
            // Hanya rotate jika bergerak (> 0.5 m/s ~ 1.8 km/h) dan bearing valid
            if (location.speed > 0.5f && location.hasBearing()) {
                rotation = -location.bearing
            }
        }

        updateAccuracyCircle(geoPoint, location.accuracy)

        // Marker selalu di layer paling atas
        mapView.overlays.remove(myLocationMarker)
        mapView.overlays.add(myLocationMarker)

        adjustZoomBasedOnAccuracy(location.accuracy)

        if (isOrientationEnabled && location.speed > 0.5f && location.hasBearing()) {
            updateNavigationMode(location)
        }

        mapView.invalidate()
    }

    private fun updateAccuracyCircle(center: GeoPoint, accuracy: Float) {
        val showCircle = accuracy < 50f

        if (accuracyCircle == null && showCircle) {
            accuracyCircle = Polygon().apply {
                fillPaint.apply {
                    style = Paint.Style.FILL
                    color = 0x332196F3.toInt()
                    isAntiAlias = true
                }
                outlinePaint.apply {
                    style = Paint.Style.STROKE
                    color = 0x882196F3.toInt()
                    strokeWidth = 2f
                    isAntiAlias = true
                }
            }
            // Tambah di index 0 agar di bawah polyline & marker
            mapView.overlays.add(0, accuracyCircle)
        }

        if (showCircle) {
            accuracyCircle?.points = Polygon.pointsAsCircle(center, accuracy.toDouble())
            accuracyCircle?.let {
                if (!mapView.overlays.contains(it)) mapView.overlays.add(0, it)
            }
        } else {
            accuracyCircle?.let { mapView.overlays.remove(it) }
        }
    }

    // ── Camera Follow ──────────────────────────────────────────────────────────

    fun smoothFollow(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        if (lastCameraPoint == null) {
            mapView.controller.animateTo(geoPoint)
            lastCameraPoint = geoPoint
            return
        }

        // Faktor lerp adaptif sesuai kecepatan — lebih cepat = follow lebih agresif
        val factor = when {
            location.speed < 2f  -> 0.08  // berjalan kaki
            location.speed < 8f  -> 0.18  // dalam kota
            location.speed < 20f -> 0.28  // jalan raya
            else                 -> 0.40  // tol / kecepatan tinggi
        }

        val smoothLat = lastCameraPoint!!.latitude +
                (geoPoint.latitude - lastCameraPoint!!.latitude) * factor
        val smoothLon = lastCameraPoint!!.longitude +
                (geoPoint.longitude - lastCameraPoint!!.longitude) * factor

        val smoothPoint = GeoPoint(smoothLat, smoothLon)
        mapView.controller.setCenter(smoothPoint)
        lastCameraPoint = smoothPoint
    }

    // ── Orientation ────────────────────────────────────────────────────────────

    fun setOrientationEnabled(enabled: Boolean) {
        isOrientationEnabled = enabled
        if (!enabled) {
            mapView.setMapOrientation(0f)
            lastBearing = 0f
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    fun clearOverlays(defaultColor: Int) {
        segments.forEach {
            mapView.overlays.remove(it.polyline)
            it.shadow?.let { s -> mapView.overlays.remove(s) }
        }
        segments.clear()
        totalPointCount = 0
        lastAddedPoint = null
        currentColor = -1

        myLocationMarker?.let { mapView.overlays.remove(it) }
        myLocationMarker = null
        accuracyCircle?.let { mapView.overlays.remove(it) }
        accuracyCircle = null

        lastCameraPoint = null
        lastBearing = 0f

        initPolyline(defaultColor)
        mapView.invalidate()
    }

    // ── Private Helpers ────────────────────────────────────────────────────────

    private fun adjustZoomBasedOnAccuracy(accuracy: Float) {
        val targetZoom = when {
            accuracy < 5f  -> 19.5
            accuracy < 10f -> 18.5
            accuracy < 20f -> 17.5
            accuracy < 50f -> 16.5
            else           -> 15.5
        }
        if (abs(targetZoom - lastZoomLevel) > ZOOM_CHANGE_THRESHOLD) {
            mapView.controller.setZoom(targetZoom)
            lastZoomLevel = targetZoom
        }
    }

    private fun updateNavigationMode(location: Location) {
        // Delta-based smoothing — hindari wrap 0°↔360° yang menyebabkan rotasi balik tiba-tiba
        val delta = ((location.bearing - lastBearing + 540f) % 360f) - 180f
        val smoothBearing = lastBearing + delta * BEARING_SMOOTH_FACTOR
        lastBearing = smoothBearing
        mapView.setMapOrientation(-smoothBearing)
    }
}