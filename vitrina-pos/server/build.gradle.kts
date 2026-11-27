// Servidor en la nube de Vitrina POS (2.0b): cuentas, equipos, sesiones, planes con módulos y una base por negocio.
// Usa el mismo núcleo (:core) que la app, así las reglas del negocio son idénticas.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val ktor = "3.0.3"

dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

application {
    mainClass.set("com.vitrinapos.server.MainKt")
    applicationName = "vitrina-server"
}

tasks.test {
    // Base de PostgreSQL para las pruebas (en GitHub la pone el flujo; aquí, la local).
    environment("TEST_DATABASE_URL", System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://127.0.0.1:5432/vitrina_test?user=vitrina&password=vitrina")
}
