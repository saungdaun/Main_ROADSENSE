package zaujaani.roadsensebasic.data.repository

import androidx.room.Embedded
import zaujaani.roadsensebasic.data.local.entity.SurveySession

data class SessionWithCount(
    @Embedded val session: SurveySession,
    val eventCount: Int
)