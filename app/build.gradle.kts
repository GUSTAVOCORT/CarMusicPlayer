plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carplayer.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carplayer.music"
        minSdk = 23          // Android 6.0 real del Allwinner T3
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Solo ARM 32 bits -> APK mucho mas liviano y sin librerias inutiles
        ndk {
            abiFilters.add("armeabi-v7a")
        }

        vectorDrawables.useSupportLibrary = true

        // Descarta idiomas que el head unit no usa -> APK mas chico
        resourceConfigurations += listOf("es", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        compose = false
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Necesario para usar APIs de java.time / streams en minSdk 23
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version",
            "META-INF/proguard/*",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }
}

dependencies {
    val media3 = "1.3.1"   // ultima rama estable con soporte comodo para API 23

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("com.google.guava:guava:32.1.3-android") // ListenableFuture del MediaController

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
