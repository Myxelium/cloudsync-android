-keep class dev.cloudsync.database.** { *; }
-keepclassmembers class * {
    @com.google.dagger.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-keep class com.charleskorn.kaml.** { *; }
-keep class kotlinx.serialization.** { *; }
