# Karoo Extension SDK — keep public API
-keep class io.hammerhead.karooext.** { *; }

# Bosch Protobuf generated code — keep entirely
-keep class com.bosch.ebike.** { *; }
-keep class com.google.protobuf.** { *; }

# Protobuf-specific rules
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.AbstractMessageLite {
    <fields>;
}

# DataStore / EncryptedSharedPreferences
-keep class androidx.datastore.** { *; }
-keep class androidx.security.crypto.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Strip Log.d/v from release builds; keep Log.i/w/e for field diagnostics
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
