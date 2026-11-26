import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Núcleo de Vitrina POS: reglas del negocio, base de datos, red, facturación electrónica y actualizaciones.
// Lo usan la app (Android, Windows, Mac) y, desde la 2.0, el servidor en la nube. No tiene pantallas.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("app.cash.sqldelight")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("app.cash.sqldelight:runtime:2.0.2")
            api("app.cash.sqldelight:coroutines-extensions:2.0.2")
            // Códigos QR y de barras (Java puro)
            api("com.google.zxing:core:3.5.3")
            // Estado observable de los estados del taller (solo el runtime, sin pantallas)
            api("org.jetbrains.compose.runtime:runtime:1.7.3")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
        }
    }
}

sqldelight {
    databases {
        create("VitrinaDb") {
            packageName.set("com.vitrinapos.db")
        }
    }
}

android {
    namespace = "com.vitrinapos.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
