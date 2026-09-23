plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}

group = "com.naua_security_mirage.app"
version = "1.3.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    
    // HTTP & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Supabase (Auth, Postgrest) & Ktor
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.6.1")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.6.1")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/jvmMain/kotlin")
            resources.srcDirs("src/jvmMain/resources")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.naua_security_mirage.app.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "NAUASecurityMirage"
            packageVersion = "1.3.0"
            description = "NAUA Security Mirage PC VPN Client"
            copyright = "© 2026 Core Dev Support"
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.prefs",
                "java.xml",
                "java.datatransfer",
                "jdk.unsupported",
                "jdk.unsupported.desktop",
                "java.management",
                "java.instrument",
                "java.net.http",
                "java.security.jgss",
                "jdk.crypto.ec"
            )
            windows {
                menuGroup = "NAUA Security Mirage"
                upgradeUuid = "D1A3F5B8-438B-4FA0-A3BB-C447BF246A10"
                iconFile.set(project.file("app.ico"))
                dirChooser = true
                perUserInstall = false
                shortcut = true
                menu = true
            }
        }
    }
}
