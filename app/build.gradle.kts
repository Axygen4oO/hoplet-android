import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.tasks.Exec
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production builds should place google-services.json in app/. Keeping this
// conditional allows OSS/debug builds and unit tests to run without secrets.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val isBundleBuild = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').startsWith("bundle", ignoreCase = true)
}

android {
    namespace = "com.wdtt.client"   
    compileSdk = 35
    
    defaultConfig {
        applicationId = "net.qwdtt.client"
        minSdk = 28
        targetSdk = 35
        versionCode = 48
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    splits {
        abi {
            // AAB already splits native libraries by ABI. AGP cannot consume the
            // multiple shrink-resource outputs produced by APK ABI splits here.
            isEnable = !isBundleBuild
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    signingConfigs {
        create("release") {
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            if (keyFile != null) {
                // Резолвим путь: если начинается с "..", берём от корня проекта
                val resolvedFile = if (keyFile.startsWith("..")) {
                    // ../release.keystore -> корень проекта / release.keystore
                    file(rootDir.resolve(keyFile.substring(3)))
                } else {
                    file(keyFile)
                }
                storeFile = resolvedFile
            }
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
            keyAlias = localProperties.getProperty("KEY_ALIAS")
            keyPassword = localProperties.getProperty("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    val releaseSigningReady = run {
        val keyFile = localProperties.getProperty("KEYSTORE_FILE")
        val resolvedFile = keyFile?.let {
            if (it.startsWith("..")) file(rootDir.resolve(it.substring(3))) else file(it)
        }
        resolvedFile?.exists() == true &&
            !localProperties.getProperty("KEYSTORE_PASSWORD").isNullOrBlank() &&
            !localProperties.getProperty("KEY_ALIAS").isNullOrBlank() &&
            !localProperties.getProperty("KEY_PASSWORD").isNullOrBlank()
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            val resolvedFile = keyFile?.let {
                if (it.startsWith("..")) file(rootDir.resolve(it.substring(3))) else file(it)
            }
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                throw GradleException("Release signing keystore or credentials are missing")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }
}

tasks.register<Exec>("buildNativeLibs") {
    group = "build"
    description = "Build Go client binaries for Android ABIs and copy them into app/src/main/jniLibs"
    workingDir = rootDir
    if (isWindows) {
        commandLine(
            "powershell",
            "-NoLogo",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootDir.resolve("scripts/build-native-libs.ps1").absolutePath
        )
    } else {
        commandLine("bash", rootDir.resolve("scripts/build-native-libs.sh").absolutePath)
    }
}

tasks.register<Exec>("buildServerAsset") {
    group = "build"
    description = "Build Linux amd64 server binary and place it into app/src/main/assets/server"
    workingDir = rootDir

    val assetDir = rootProject.file("app/src/main/assets")
    val serverTmp = assetDir.resolve("server.tmp")
    val serverAsset = assetDir.resolve("server")

    doFirst {
        assetDir.mkdirs()
        if (serverTmp.exists()) serverTmp.delete()
        if (serverAsset.exists()) serverAsset.delete()
    }

    commandLine(
        "go",
        "build",
        "-trimpath",
        "-buildvcs=false",
        "-o",
        serverTmp.absolutePath,
        "."
    )

    environment("GOOS", "linux")
    environment("GOARCH", "amd64")
    environment("CGO_ENABLED", "0")

    doLast {
        if (!serverTmp.exists()) {
            throw GradleException("buildServerAsset did not produce ${serverTmp.absolutePath}")
        }
        Files.move(
            serverTmp.toPath(),
            serverAsset.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn("buildNativeLibs", "buildServerAsset")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.github.mwiede:jsch:0.2.16")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
