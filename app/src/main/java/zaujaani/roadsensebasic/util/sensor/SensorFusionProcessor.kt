package zaujaani.roadsensebasic.util.sensor

class SensorFusionProcessor {

    private var lastValue: Float = 0f
    private var initialized = false

    fun fuseVibration(raw: Float): Float {
        val alpha = 0.2f

        if (!initialized) {
            lastValue = raw
            initialized = true
            return raw
        }

        lastValue = alpha * raw + (1 - alpha) * lastValue
        return lastValue
    }

    fun reset() {
        lastValue = 0f
        initialized = false
    }
}