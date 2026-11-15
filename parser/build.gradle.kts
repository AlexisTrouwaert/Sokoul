plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets["main"].java.exclude("**/* - Copie.kt", "**/* - copy.kt")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/* - Copie.kt", "**/* - copy.kt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    systemProperty("realWorld", System.getProperty("realWorld") ?: "false")
    testLogging { showStandardStreams = true }
    maxHeapSize = "2g"
}

dependencies {
    // XmlPullParser est fourni par Android ; on ne compile que contre l'interface.
    compileOnly(libs.xmlpull)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
}
