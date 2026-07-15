import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets live in local.properties (gitignored, machine-specific — same file Android already uses
// for sdk.dir) rather than in tracked source, so a real SMTP credential never has to touch git
// history. See local.properties.example for the keys this project expects and when to set them.
// Missing keys fall back to the same "TODO-operator-supplied" placeholder AppConfig.kt used to
// hardcode — syntactically valid, fails at runtime (a bad SMTP login) rather than at build time,
// consistent with this project's existing placeholder convention for not-yet-provisioned secrets.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
fun localProperty(key: String, default: String = "TODO-operator-supplied"): String =
    (localProperties.getProperty(key) ?: System.getenv(key) ?: default)

android {
    namespace = "com.ris.imagedistributor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ris.imagedistributor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SMTP_USERNAME", "\"${localProperty("SMTP_USERNAME")}\"")
        buildConfigField("String", "SMTP_APP_PASSWORD", "\"${localProperty("SMTP_APP_PASSWORD")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            // com.sun.mail:android-mail + android-activation both ship this. [Story 2.2]
            excludes += "META-INF/NOTICE.md"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.android.mail)
    implementation(libs.android.mail.activation)
    implementation(libs.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
