import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

android {
    namespace = "com.naua_security_mirage.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.naua_security_mirage.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("mirage-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                val keyPropsFile = rootProject.file("key.properties")
                if (keyPropsFile.exists()) {
                    val props = Properties()
                    keyPropsFile.inputStream().use { stream ->
                        props.load(stream)
                    }
                    storePassword = props.getProperty("storePassword", "")
                    keyAlias = props.getProperty("keyAlias", "mirage")
                    keyPassword = props.getProperty("keyPassword", storePassword)
                } else {
                    storePassword = project.findProperty("MIRAGE_STORE_PASSWORD") as String?
                        ?: System.getenv("MIRAGE_STORE_PASSWORD") ?: ""
                    keyAlias = project.findProperty("MIRAGE_KEY_ALIAS") as String?
                        ?: System.getenv("MIRAGE_KEY_ALIAS") ?: "mirage"
                    keyPassword = project.findProperty("MIRAGE_KEY_PASSWORD") as String?
                        ?: System.getenv("MIRAGE_KEY_PASSWORD") ?: storePassword
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val keystoreFile = file("mirage-release.jks")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xskip-metadata-version-check"
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libgojni.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // RuStore In-App Update SDK
    implementation("ru.rustore.sdk:appupdate:10.5.1")

    // Firebase SDK with BoM
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-perf")

    // HTTP & JSON Serialization for VLESS registration & ping
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
