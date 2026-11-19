# Car App Library: the host instantiates/reflects on templates and the service.
-keep class androidx.car.app.** { *; }
-keep class app.boophis.car.BoophisCarAppService { *; }
# FFmpeg decoder JNI
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
