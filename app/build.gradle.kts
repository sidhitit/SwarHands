plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.demo.swarhands"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.demo.swarhands"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // keep the MediaPipe model uncompressed so it can be memory-mapped
        noCompress.add("task")
    }
}

// Downloads Google's free hand landmark model into assets on first build (needs internet once).
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val handModelFile = file("src/main/assets/hand_landmarker.task")

val downloadHandModel by tasks.registering {
    description = "Downloads the MediaPipe hand_landmarker.task model"
    outputs.file(handModelFile)
    onlyIf { !handModelFile.exists() }
    doLast {
        handModelFile.parentFile.mkdirs()
        val tmp = File(handModelFile.parentFile, "hand_landmarker.task.part")
        uri(handModelUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        tmp.renameTo(handModelFile)
        println("Downloaded hand model -> ${handModelFile.absolutePath}")
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
