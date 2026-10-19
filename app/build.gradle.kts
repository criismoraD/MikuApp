import java.net.Socket
import java.net.URI
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.secrets)
}

val keystoreFile = rootProject.file("debug.keystore")
val keystoreBase64File = rootProject.file("debug.keystore.base64")
if (!keystoreFile.exists() && keystoreBase64File.exists()) {
    val decoded = Base64.getMimeDecoder().decode(keystoreBase64File.readText())
    keystoreFile.writeBytes(decoded)
}

val webServerFile = rootProject.file("server.mjs")
if (webServerFile.exists()) {
    val isListening = runCatching {
        val s = Socket("127.0.0.1", 3000)
        s.close()
        true
    }.getOrDefault(false)
    if (!isListening) {
        runCatching {
            ProcessBuilder("node", webServerFile.absolutePath)
                .directory(rootProject.projectDir)
                .redirectOutput(File("/tmp/miku-web.log"))
                .redirectErrorStream(true)
                .start()
        }
    }
}

val sherpaAarFile = file("libs/sherpa-onnx-1.13.8.aar")
if (!sherpaAarFile.exists()) {
    sherpaAarFile.parentFile.mkdirs()
    val aarUrls = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
        "https://sourceforge.net/projects/sherpa-onnx/files/v1.13.8/sherpa-onnx-1.13.8.aar/download"
    )
    for (url in aarUrls) {
        try {
            println("Descargando sherpa-onnx-1.13.8.aar desde $url ...")
            URI(url).toURL().openStream().use { input ->
                sherpaAarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (sherpaAarFile.exists() && sherpaAarFile.length() > 100_000) {
                println("sherpa-onnx-1.13.8.aar descargado correctamente (${sherpaAarFile.length()} bytes)")
                break
            }
        } catch (e: Exception) {
            println("Aviso: fallo descargando AAR desde $url: ${e.message}")
            sherpaAarFile.delete()
        }
    }
}

val onnxModelFile = file("src/main/assets/vits-piper-es_MX-claude-high/es_MX-claude-high.onnx")
if (onnxModelFile.exists()) {
    onnxModelFile.delete()
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}

android {
    namespace = "com.example.mikuapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mikuapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
