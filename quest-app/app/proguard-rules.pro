# WebRTC Keep Rules
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp Keep Rules
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.metaquest.cast.network.** { *; }
-keep class com.metaquest.cast.model.** { *; }
