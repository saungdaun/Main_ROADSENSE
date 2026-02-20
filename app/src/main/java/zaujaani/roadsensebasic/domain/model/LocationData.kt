package zaujaani.roadsensebasic.domain.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val accuracy: Float
)