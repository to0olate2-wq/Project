# Keep class and method names: crash reports shown in the app stay readable.
-dontobfuscate

# Saved state, caches and guide models (kotlinx.serialization + our binary cache).
-keep class app.falak.player.data.** { *; }

# FFmpeg audio decoder: loaded by reflection from Media3 and called back from native code.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Optional TLS providers OkHttp looks for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
