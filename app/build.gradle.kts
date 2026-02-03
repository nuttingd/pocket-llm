import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun envOrProp(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

val releaseStoreFile = envOrProp("RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = envOrProp("RELEASE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = envOrProp("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = envOrProp("RELEASE_KEY_PASSWORD", "keyPassword")

android {
    namespace = "dev.nutting.pocketllm"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nutting.pocketllm"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            signingConfig = try {
                signingConfigs.getByName("release")
            } catch (_: UnknownDomainObjectException) {
                signingConfigs.getByName("debug")
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)

    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Compose UI
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")

    // Navigation (type-safe)
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Room (database)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Ktor (HTTP + SSE)
    implementation("io.ktor:ktor-client-core:3.4.0")
    implementation("io.ktor:ktor-client-okhttp:3.4.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DataStore (settings + encrypted storage)
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Tink (encryption for API keys)
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.39.2")

    // Image loading
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.ktor:ktor-client-mock:3.4.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.test:core:1.6.1")
}
