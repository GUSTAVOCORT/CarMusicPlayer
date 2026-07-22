# Car Music Player (Allwinner T3 / Android 6-7)

## Importar en Android Studio
1. Crea un proyecto nuevo vacio ("No Activity", Kotlin, Views) con package `com.carplayer.music`.
2. Reemplaza `app/build.gradle.kts`, `app/src/main` y `app/proguard-rules.pro` por los de este ZIP.
3. En el `settings.gradle.kts` raiz deja `google()` y `mavenCentral()` en `dependencyResolutionManagement`.
4. Sync + Run.

## Estructura
```
app/
 ├─ build.gradle.kts
 ├─ proguard-rules.pro
 └─ src/main/
     ├─ AndroidManifest.xml
     ├─ java/com/carplayer/music/
     │   ├─ App.kt
     │   ├─ player/MusicService.kt      <- Foreground MediaSessionService
     │   ├─ player/PlayerBus.kt
     │   ├─ scanner/UsbScanner.kt       <- MediaStore + walk de montajes OTG
     │   ├─ scanner/Song.kt             <- modelo + indice global
     │   ├─ ui/MainActivity.kt
     │   ├─ ui/BrowserAdapter.kt        <- ListAdapter + DiffUtil
     │   └─ view/AudioVisualizerView.kt <- Canvas 30 FPS
     └─ res/{layout,values,drawable,xml}
```

## Instalar en el head unit
Genera el APK release (`armeabi-v7a`, minify ON), copialo al pendrive e instalalo
con el explorador del equipo. Si el firmware bloquea instalaciones, activa
"Origenes desconocidos" en Ajustes > Seguridad.
