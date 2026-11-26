# Preserve Hiddify Core libbox JNI & Go-bound classes; the bridge accesses these reflectively.
-keep class com.hiddify.core.libbox.** { *; }
-keep class com.hiddify.core.mobile.** { *; }
-keep class go.** { *; }
-dontwarn com.hiddify.core.libbox.**
-dontwarn com.hiddify.core.mobile.**
-dontwarn go.**

# Preserve Tor Android JNI & service classes
-keep class org.torproject.jni.** { *; }
-dontwarn org.torproject.jni.**

# Preserve Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.visionvpn.app.data.model.**$$serializer { *; }
-keepclassmembers class com.visionvpn.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.visionvpn.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * implements kotlinx.coroutines.CoroutineExceptionHandler {
    *;
}
