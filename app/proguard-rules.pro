# proguard-rules.pro

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Room
-keep class androidx.room.** { *; }

# Keep Kotlin
-keepclassmembers class kotlin.Metadata { *; }

# Keep our app
-keep class zaujaani.roadsensebasic.data.local.entity.** { *; }
-keepclassmembers class zaujaani.roadsensebasic.** { *; }

# Optimize
-optimizationpasses 5
-dontobfuscate