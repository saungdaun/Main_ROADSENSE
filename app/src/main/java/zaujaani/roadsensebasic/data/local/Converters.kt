package zaujaani.roadsensebasic.data.local

import androidx.room.TypeConverter
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.Surface
import java.time.Instant
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    // Converter untuk Instant (Java 8 Time)
    @TypeConverter
    fun fromInstant(value: Instant?): Long? {
        return value?.toEpochMilli()
    }

    @TypeConverter
    fun toInstant(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }

    // Converter untuk Condition enum
    @TypeConverter
    fun fromCondition(condition: Condition?): String? {
        return condition?.name
    }

    @TypeConverter
    fun toCondition(name: String?): Condition? {
        return name?.let { Condition.valueOf(it) }
    }

    // Converter untuk Surface enum
    @TypeConverter
    fun fromSurface(surface: Surface?): String? {
        return surface?.name
    }

    @TypeConverter
    fun toSurface(name: String?): Surface? {
        return name?.let { Surface.valueOf(it) }
    }
}