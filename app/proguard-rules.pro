# Media3 / ExoPlayer
-dontwarn com.google.common.**
-keep class androidx.media3.** { *; }
-keepclassmembers class * extends androidx.media3.session.MediaSessionService { *; }
# Custom View inflada desde XML
-keep class com.carplayer.music.view.AudioVisualizerView { public <init>(...); }
