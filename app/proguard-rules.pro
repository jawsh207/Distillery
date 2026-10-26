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

# WorkManager stores its jobs in a Room database that it creates by reflection
# (WorkDatabase_Impl's no-argument constructor). R8 full mode removes that
# constructor unless it's kept, which crashes the app at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# Workers are created by reflection from their class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Startup initializers are created by reflection from the manifest.
-keep class * implements androidx.startup.Initializer { <init>(); }
