import java.util.Properties

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

group = "com.naua_security_mirage.app"
version = "1.4.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.12.2")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.6.1")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.6.1")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
}

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

// Конфигурационные секреты (адрес API бесплатных узлов, Supabase, VLESS серверы)
// не хранятся в репозитории: они приходят из секретов CI и внедряются в ресурс
// mirage.properties только на этапе сборки.
val generateMirageConfig = tasks.register("generateMirageConfig") {
    val apiUrl = getSecret("MIRAGE_API_URL")
    val supabaseUrl = getSecret("SUPABASE_URL")
    val supabaseAnonKey = getSecret("SUPABASE_ANON_KEY")
    val franceHost = getSecret("FRANCE_HOST")
    val francePort = getSecret("FRANCE_PORT", "443")
    val francePbk = getSecret("FRANCE_PBK")
    val franceSni = getSecret("FRANCE_SNI")
    val franceSid = getSecret("FRANCE_SID")
    val franceSpx = getSecret("FRANCE_SPX", "/")
    val franceFingerprint = getSecret("FRANCE_FINGERPRINT", "chrome")
    val franceDefaultUuid = getSecret("FRANCE_DEFAULT_UUID")
    val franceEndpoints = getSecret("FRANCE_ENDPOINTS")
    val yooMoneyWallet = getSecret("YOOMONEY_WALLET")

    inputs.property("apiUrl", apiUrl)
    inputs.property("supabaseUrl", supabaseUrl)
    inputs.property("supabaseAnonKey", supabaseAnonKey)
    inputs.property("franceHost", franceHost)
    inputs.property("francePort", francePort)
    inputs.property("francePbk", francePbk)
    inputs.property("franceSni", franceSni)
    inputs.property("franceSid", franceSid)
    inputs.property("franceSpx", franceSpx)
    inputs.property("franceFingerprint", franceFingerprint)
    inputs.property("franceDefaultUuid", franceDefaultUuid)
    inputs.property("franceEndpoints", franceEndpoints)
    inputs.property("yooMoneyWallet", yooMoneyWallet)

    val outputDir = layout.buildDirectory.dir("generated/mirage-config")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("mirage.properties").asFile
        file.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("apiUrl", apiUrl)
        props.setProperty("supabaseUrl", supabaseUrl)
        props.setProperty("supabaseAnonKey", supabaseAnonKey)
        props.setProperty("franceHost", franceHost)
        props.setProperty("francePort", francePort)
        props.setProperty("francePbk", francePbk)
        props.setProperty("franceSni", franceSni)
        props.setProperty("franceSid", franceSid)
        props.setProperty("franceSpx", franceSpx)
        props.setProperty("franceFingerprint", franceFingerprint)
        props.setProperty("franceDefaultUuid", franceDefaultUuid)
        props.setProperty("franceEndpoints", franceEndpoints)
        props.setProperty("yooMoneyWallet", yooMoneyWallet)
        file.outputStream().use { props.store(it, "Generated Mirage Properties") }
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/jvmMain/kotlin")
            resources.srcDirs("src/jvmMain/resources", generateMirageConfig)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.naua_security_mirage.app.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "NAUASecurityMirage"
            packageVersion = "1.4.0"
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
