import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.application")
}

// La versión se escribe en un solo lugar: core/src/commonMain/kotlin/com/vitrinapos/AppInfo.kt
val appInfo = rootProject.file("core/src/commonMain/kotlin/com/vitrinapos/AppInfo.kt").readText()
val appVersionName = Regex("VERSION = \"([^\"]+)\"").find(appInfo)!!.groupValues[1]
val releaseProps = Properties().apply {
    rootProject.file("release/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val ksFile: String? = System.getenv("VITRINA_KEYSTORE_FILE") ?: releaseProps.getProperty("storeFile")?.let { rootProject.file("release/$it").path }
val appVersionCode = Regex("BUILD = (\\d+)").find(appInfo)!!.groupValues[1].toInt()

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            // Códigos QR y de barras (Java puro: funciona en Android y en Windows)
            implementation("com.google.zxing:core:3.5.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.exifinterface:exifinterface:1.3.7")
            implementation("app.cash.sqldelight:android-driver:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
            // Escáner continuo con la cámara
            implementation("androidx.camera:camera-camera2:1.3.4")
            implementation("androidx.camera:camera-lifecycle:1.3.4")
            implementation("androidx.camera:camera-view:1.3.4")
            implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
            // Lector de certificados .p12 modernos (AES/PBES2) que Android 8–11 no abre solo
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            implementation("org.apache.pdfbox:pdfbox:3.0.3")
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation("com.google.zxing:javase:3.5.3")
            }
        }
    }
}

android {
    namespace = "com.vitrinapos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vitrinapos.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    // Firma propia de la app (la misma siempre: sin ella Android no acepta las actualizaciones).
    // Local: release/keystore.properties (no se sube al repositorio). En GitHub: secretos VITRINA_KEYSTORE_*.
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("VITRINA_KEYSTORE_PASSWORD") ?: releaseProps.getProperty("storePassword")
            keyAlias = System.getenv("VITRINA_KEY_ALIAS") ?: releaseProps.getProperty("keyAlias") ?: "androiddebugkey"
            keyPassword = System.getenv("VITRINA_KEY_PASSWORD") ?: releaseProps.getProperty("keyPassword") ?: storePassword
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin la firma propia (por ejemplo, en una compilación de prueba) se usa la de depuración.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.vitrinapos.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Vitrina POS"
            packageVersion = appVersionName
            description = "Punto de venta con catálogo por fotos"
            vendor = "Vitrina POS"
            modules("java.sql", "java.desktop")
            windows {
                iconFile.set(project.file("icons/vitrina-pos.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                menuGroup = "Vitrina POS"
                upgradeUuid = "7d8a2b0e-4c1f-4a8e-9a2d-3f1b6c5e9a10"
            }
            macOS {
                iconFile.set(project.file("icons/vitrina-pos.icns"))
                bundleID = "com.vitrinapos.app"
                appCategory = "public.app-category.business"
                // macOS exige que la versión empiece en 1 o más (1.10.1 está bien).
                packageVersion = appVersionName
            }
        }
    }
}

// Capturas de pantalla: ./gradlew :composeApp:desktopTest -Dscreenshots=/ruta/carpeta
tasks.withType<Test>().configureEach {
    // En GitHub se ve el motivo exacto de una prueba que falla.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; events("failed") }
    systemProperty("vitrina.noUpdates", "true")
    System.getProperty("screenshots")?.let { systemProperty("screenshots", it) }
    listOf("phoneCode", "phonePort", "labeldebug", "fiscalOut", "dgiiLive").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    outputs.upToDateWhen { System.getProperty("screenshots") == null && System.getProperty("phoneCode") == null && System.getProperty("dgiiLive") == null }
}
