# Moonshine's JNI layer creates and fills Java objects (Transcript,
# TranscriptLine, WordTiming, ...) by class and field name from native code,
# so none of the binding may be renamed or removed.
-keep class ai.moonshine.voice.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp (used by Moonshine's AssetDownloader) references optional TLS
# providers that aren't on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
