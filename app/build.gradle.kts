import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val signing = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val projectVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
fun signingValue(name: String): String? = providers.environmentVariable(name).orNull
    ?: signing.getProperty(name)
val uploadStore = signingValue("PLAY_UPLOAD_STORE_FILE")
val uploadStorePassword = signingValue("PLAY_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = signingValue("PLAY_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = signingValue("PLAY_UPLOAD_KEY_PASSWORD")
val signingConfigured = listOf(uploadStore, uploadStorePassword, uploadKeyAlias, uploadKeyPassword).all { !it.isNullOrBlank() }
val releaseApiBase = secrets.getProperty("FISH_ID_API_BASE_URL", "https://fishing.fishnz.space")
fun quotedBuildValue(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "nz.fishingnz.app"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "nz.fishingnz.app"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("playVersionCode").orNull?.toInt()
            ?: projectVersion.getProperty("versionCode").toInt()
        versionName = providers.gradleProperty("playVersionName").orNull
            ?: projectVersion.getProperty("versionName")
        buildConfigField("String", "FISH_ID_API_BASE_URL", quotedBuildValue(releaseApiBase))
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (signingConfigured) create("playUpload") {
            storeFile = rootProject.file(uploadStore!!)
            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (signingConfigured) signingConfig = signingConfigs.getByName("playUpload")
        }
    }

}

val verifyPlayRelease by tasks.registering {
    group = "verification"
    description = "Check production configuration and upload-key signing before a Play release."
    doLast {
        val endpoint = URI(releaseApiBase)
        check(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null
            && endpoint.query == null && endpoint.fragment == null && endpoint.path.orEmpty().trim('/').isEmpty()) {
            "Set FISH_ID_API_BASE_URL to a public HTTPS origin in secrets.properties."
        }
        val firebase = file("google-services.json")
        check(firebase.exists() && !firebase.readText().contains("CI_BUILD_ONLY") && !firebase.readText().contains("catchcheck-ci-build")) {
            "A real app/google-services.json is required for a release."
        }
        check(android.defaultConfig.versionCode!! > 0) { "playVersionCode must be a positive integer." }
        val allowUnsigned = providers.gradleProperty("allowUnsignedRelease").orNull == "true"
        check(signingConfigured || allowUnsigned) {
            "Configure the four PLAY_UPLOAD_* values in signing.properties or the environment. Unsigned audit builds require -PallowUnsignedRelease=true and cannot be uploaded to Play."
        }
        if (signingConfigured) check(rootProject.file(uploadStore!!).isFile) { "The configured Play upload keystore does not exist." }
        if (!signingConfigured) logger.warn("UNSIGNED audit build: do not upload this bundle to Google Play.")
    }
}
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(verifyPlayRelease) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.firebase:firebase-analytics")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real JSON parsing for frozen provider-response regression tests (Android stubs cannot parse).
    testImplementation("org.json:json:20180813")
}
