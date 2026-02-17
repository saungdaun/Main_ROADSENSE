package zaujaani.roadsensebasic.util

import android.location.Location

fun Location.toFormattedString(): String {
    return "Lat: $latitude, Lng: $longitude, Acc: $accuracy m"
}