# =============================================================================
# MedTrack – ProGuard / R8 Rules
# android/app/proguard-rules.pro
# =============================================================================

# ── Keep application class ────────────────────────────────────────────────────
-keep class com.joechrist.medtrack.** { *; }

# ── Kotlin serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.joechrist.medtrack.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# ── Retrofit + OkHttp ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# ── Firebase ──────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class *

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── CameraX ───────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }

# ── Remove logging in release ─────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
