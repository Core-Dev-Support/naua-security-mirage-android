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

    defaultConfig {
        applicationId = "com.naua_security_mirage.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${getSecret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${getSecret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "THREE_X_UI_BASE_URL", "\"${getSecret("THREE_X_UI_BASE_URL")}\"")
        buildConfigField("String", "THREE_X_UI_USERNAME", "\"${getSecret("THREE_X_UI_USERNAME")}\"")
        buildConfigField("String", "THREE_X_UI_PASSWORD", "\"${getSecret("THREE_X_UI_PASSWORD")}\"")
        buildConfigField("int", "THREE_X_UI_INBOUND_ID", getSecret("THREE_X_UI_INBOUND_ID", "2").ifEmpty { "2" })
        buildConfigField("String", "FRANCE_HOST", "\"${getSecret("FRANCE_HOST")}\"")
        buildConfigField("int", "FRANCE_PORT", getSecret("FRANCE_PORT", "443").ifEmpty { "443" })
        buildConfigField("String", "FRANCE_PBK", "\"${getSecret("FRANCE_PBK")}\"")
        buildConfigField("String", "FRANCE_SNI", "\"${getSecret("FRANCE_SNI")}\"")
        buildConfigField("String", "FRANCE_SID", "\"${getSecret("FRANCE_SID")}\"")
        buildConfigField("String", "FRANCE_SPX", "\"${getSecret("FRANCE_SPX", "/")}\"")
        buildConfigField("String", "FRANCE_FINGERPRINT", "\"${getSecret("FRANCE_FINGERPRINT", "chrome")}\"")
        buildConfigField("String", "FRANCE_DEFAULT_UUID", "\"${getSecret("FRANCE_DEFAULT_UUID")}\"")
        buildConfigField("String", "YOOMONEY_WALLET", "\"${getSecret("YOOMONEY_WALLET")}\"")
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

    // Firebase SDK with BoM
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-perf")

    // HTTP & JSON Serialization for VLESS registration & ping
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}
