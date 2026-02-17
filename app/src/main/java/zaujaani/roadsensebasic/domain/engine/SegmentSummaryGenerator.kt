package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SegmentSummaryGenerator @Inject constructor() {
    data class SegmentSummary(
        val segment: RoadSegment,
        val length: Double,
        val conditionDistribution: Map<String, Double>,
        val avgConfidence: Int
    )

    fun generateSummary(segment: RoadSegment, telemetries: List<Any>): SegmentSummary {
        return SegmentSummary(
            segment = segment,
            length = segment.endDistance - segment.startDistance,
            conditionDistribution = mapOf(segment.conditionAuto to 100.0),
            avgConfidence = segment.confidence
        )
    }
}