import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

android {
    namespace = "com.naua_security_mirage.app"
    compileSdk = 34

    fun getSecret(name: String, default: String = ""): String {
        val prop = project.findProperty(name) as String?
        if (!prop.isNullOrEmpty()) return prop.trim()
        val env = System.getenv(name)
        if (!env.isNullOrEmpty()) return env.trim()
        val localProps = rootProject.file("local.properties")
        if (localProps.exists()) {
            val p = Properties()
            localProps.inputStream().use { p.load(it) }
            val v = p.getProperty(name)
            if (!v.isNullOrEmpty()) return v.trim()
        }
        return default.trim()
    }

    fun gitHash(): String {
        val env = System.getenv("COMMIT_HASH")
        if (!env.isNullOrEmpty()) return env.trim().take(12)
        return try {
            val text = project.providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
            if (text.isNotBlank()) text else "dev"
        } catch (_: Throwable) {
            "dev"
        }
    }

    defaultConfig {
        applicationId = "com.naua_security_mirage.app"
        minSdk = 23

        targetSdk = 35
        compileSdk = 35

        versionCode = 6
        versionName = "1.5.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${getSecret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${getSecret("SUPABASE_ANON_KEY")}\"")

        buildConfigField("String", "FRANCE_HOST", "\"${getSecret("FRANCE_HOST")}\"")
        buildConfigField("int", "FRANCE_PORT", getSecret("FRANCE_PORT", "443").ifEmpty { "443" })
        buildConfigField("String", "FRANCE_PBK", "\"${getSecret("FRANCE_PBK")}\"")
        buildConfigField("String", "FRANCE_SNI", "\"${getSecret("FRANCE_SNI")}\"")
        buildConfigField("String", "FRANCE_SID", "\"${getSecret("FRANCE_SID")}\"")
        buildConfigField("String", "FRANCE_SPX", "\"${getSecret("FRANCE_SPX", "/")}\"")
        buildConfigField("String", "FRANCE_FINGERPRINT", "\"${getSecret("FRANCE_FINGERPRINT", "chrome")}\"")

        buildConfigField(
            "String",
            "FRANCE_ENDPOINTS",
            "\"${getSecret("FRANCE_ENDPOINTS").replace("\"", "")}\""
        )
        buildConfigField("String", "YOOMONEY_WALLET", "\"${getSecret("YOOMONEY_WALLET")}\"")
        buildConfigField("boolean", "USE_LIBXRAY_CONVERTER", getSecret("USE_LIBXRAY_CONVERTER", "false"))
        buildConfigField("String", "GIT_HASH", "\"${gitHash()}\"")
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

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Основной APK для современных устройств — arm64 (~17 МБ вместо ~29 МБ).
    // armeabi-v7a остаётся отдельным файлом для старых телефонов, universal —
    // страховка для нестандартных ABI и старых клиентов обновления.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            buildConfigField("boolean", "LOGS_ENABLED", "true")
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
            buildConfigField("boolean", "LOGS_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xskip-metadata-version-check"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
  implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-perf")
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
