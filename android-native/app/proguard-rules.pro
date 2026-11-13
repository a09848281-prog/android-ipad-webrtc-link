# Keep WebRTC native/JNI entry points intact when release shrinking is enabled later.
-keep class org.webrtc.** { *; }
-keepclasseswithmembers class * { native <methods>; }
