import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The version comes from the release tag on CI (-PappVersion=2.1); the build number follows from it,
// so a newer version always has a higher versionCode (2.1 -> 20100, 2.1.3 -> 20103).
// A build without it (Android Studio) is "<last tag>.99-dev": it installs over that release,
// and the next release installs over it (2.1.99-dev -> 20199, between 2.1 and 2.2).
val lastTag: String? = try {
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").takeIf { Regex("\\d+(\\.\\d+)*").matches(it) }
} catch (e: Exception) {
    null
}
val appVersion: String = (findProperty("appVersion") as String?)?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: lastTag?.let { tag -> tag.split('.').let { (it + listOf("0", "0")).take(2) }.joinToString(".") + ".99-dev" }
    ?: "2.0"
val appVersionCode: Int = appVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10_000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

// Signing with the release key: on CI from the environment (GitHub secrets), on a computer from
// local.properties (not in git). With the same key, builds from Android Studio install over the
// app from GitHub and the other way round. Without either, the usual debug key signs.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

class Signing(val store: String, val storePassword: String, val alias: String, val keyPassword: String)

val signing: Signing? = run {
    val env = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
    val store = env ?: localProperties.getProperty("signing.storeFile")?.takeIf { it.isNotBlank() }
    fun value(envKey: String, localKey: String) =
        (if (env != null) System.getenv(envKey) else localProperties.getProperty(localKey))?.takeIf { it.isNotEmpty() }
    val storePassword = value("SIGNING_STORE_PASSWORD", "signing.storePassword")
    val alias = value("SIGNING_KEY_ALIAS", "signing.keyAlias")
    val keyPassword = value("SIGNING_KEY_PASSWORD", "signing.keyPassword") ?: storePassword
    if (store != null && file(store).exists() && storePassword != null && alias != null && keyPassword != null) {
        Signing(store, storePassword, alias, keyPassword)
    } else null
}

android {
    namespace = "cz.natsu.automation"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.natsu.automation"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.store)
                storePassword = signing.storePassword
                keyAlias = signing.alias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
        release {
            if (signing != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // JVM unit tests: Android stubs return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    // Scrolling lists in home screen widgets also on Android 9-11 (RemoteCollectionItems).
    implementation("androidx.core:core-remoteviews:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Drag & drop reordering in LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // XML theme (Theme.Material3.*) and DynamicColors for the window
    implementation("com.google.android.material:material:1.14.0")

    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // QR and bar codes ("Vytvořit QR kód", "Přečíst kód z obrázku"); plain Java, no Google services.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
