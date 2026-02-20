package zaujaani.roadsensebasic.util

import android.graphics.Color
import android.location.Location
import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class MapRenderer(private val mapView: MapView) {
    private companion object {
        const val MIN_DISTANCE_METERS = 2.0
        const val MAX_PATH_POINTS = 5000
    }

    private val pathPoints = mutableListOf<GeoPoint>()
    private val polylines = mutableListOf<Polyline>()
    private var currentPolyline: Polyline? = null
    private var currentColor: Int? = null

    private var myLocationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    private var lastCameraPoint: GeoPoint? = null
    private var lastZoomLevel: Double = 18.0
    private var lastBearing = 0f
    private var isOrientationEnabled = true

    fun initPolyline(defaultColor: Int) {
        startNewPolyline(defaultColor)
    }

    private fun startNewPolyline(color: Int) {
        val polyline = Polyline().apply {
            outlinePaint.color = color
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        polylines.add(polyline)
        mapView.overlays.add(polyline)
        currentPolyline = polyline
        currentColor = color
    }

    fun addGeoPoint(geoPoint: GeoPoint, conditionColor: Int) {
        // Skip if too close to last point
        if (pathPoints.isNotEmpty() && pathPoints.last().distanceToAsDouble(geoPoint) < MIN_DISTANCE_METERS) return

        // Start new polyline if color changed
        if (conditionColor != currentColor) {
            startNewPolyline(conditionColor)
        }

        pathPoints.add(geoPoint)
        currentPolyline?.setPoints(pathPoints.toList())

        // Limit path points to avoid memory issues
        if (pathPoints.size > MAX_PATH_POINTS) {
            pathPoints.removeAt(0)
        }

        mapView.invalidate()
    }

    fun updateMyLocation(location: Location, myLocationIcon: android.graphics.drawable.Drawable?) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        if (myLocationMarker == null) {
            myLocationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = myLocationIcon
                isFlat = true
                mapView.overlays.add(this)
            }
        }
        myLocationMarker?.apply {
            position = geoPoint
            rotation = location.bearing
        }

        // Update accuracy circle (create once, then update points)
        if (accuracyCircle == null) {
            accuracyCircle = Polygon().apply {
                fillPaint.apply {
                    style = android.graphics.Paint.Style.FILL
                    color = 0x332196F3.toInt()
                    isAntiAlias = true
                }
                outlinePaint.apply {
                    style = android.graphics.Paint.Style.STROKE
                    color = 0x882196F3.toInt()
                    strokeWidth = 2f
                    isAntiAlias = true
                }
            }
            accuracyCircle?.let { mapView.overlays.add(it) }
        }
        accuracyCircle?.points = Polygon.pointsAsCircle(geoPoint, location.accuracy.toDouble())

        // Ensure marker stays on top
        myLocationMarker?.let { marker ->
            mapView.overlays.remove(marker)
            mapView.overlays.add(marker)
        }

        adjustZoomBasedOnAccuracy(location.accuracy)
        if (isOrientationEnabled) {
            updateNavigationMode(location)
        }
        mapView.invalidate()
    }

    fun smoothFollow(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        if (lastCameraPoint == null) {
            mapView.controller.setCenter(geoPoint)
            lastCameraPoint = geoPoint
            return
        }
        val factor = when {
            location.speed < 3f -> 0.1
            location.speed < 10f -> 0.2
            else -> 0.3
        }
        val lat = lastCameraPoint!!.latitude +
                (geoPoint.latitude - lastCameraPoint!!.latitude) * factor
        val lon = lastCameraPoint!!.longitude +
                (geoPoint.longitude - lastCameraPoint!!.longitude) * factor
        val smoothPoint = GeoPoint(lat, lon)
        mapView.controller.setCenter(smoothPoint)
        lastCameraPoint = smoothPoint
    }

    fun setOrientationEnabled(enabled: Boolean) {
        isOrientationEnabled = enabled
        if (!enabled) {
            mapView.setMapOrientation(0f)
        }
    }

    fun clearOverlays(defaultColor: Int) {
        // Remove all polylines
        polylines.forEach { mapView.overlays.remove(it) }
        polylines.clear()
        pathPoints.clear()
        currentPolyline = null
        currentColor = null

        // Reinitialize with default color
        initPolyline(defaultColor)

        mapView.invalidate()
    }

    private fun adjustZoomBasedOnAccuracy(accuracy: Float) {
        val targetZoom = when {
            accuracy < 5 -> 19.5
            accuracy < 10 -> 18.5
            accuracy < 20 -> 17.5
            accuracy < 50 -> 16.5
            else -> 15.5
        }
        if (kotlin.math.abs(targetZoom - lastZoomLevel) > 0.5) {
            mapView.controller.setZoom(targetZoom)
            lastZoomLevel = targetZoom
        }
    }

    private fun updateNavigationMode(location: Location) {
        val smoothBearing = lastBearing + (location.bearing - lastBearing) * 0.2f
        lastBearing = smoothBearing
        mapView.setMapOrientation(-smoothBearing)
    }
}