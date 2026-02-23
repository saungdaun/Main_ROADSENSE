package zaujaani.roadsensebasic.data.repository

import androidx.room.Embedded
import androidx.room.ColumnInfo
import zaujaani.roadsensebasic.data.local.entity.SurveySession

data class SessionWithCount(

    @Embedded
    val session: SurveySession,

    @ColumnInfo(name = "eventCount")
    val eventCount: Int = 0,

    @ColumnInfo(name = "photoCount")
    val photoCount: Int = 0,

    @ColumnInfo(name = "audioCount")
    val audioCount: Int = 0
)