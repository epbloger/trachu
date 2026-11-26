plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.room")
}

// A release build never serves ads until its own RSYA banner is configured explicitly.
val trachuAdsEnabled = providers.gradleProperty("trachuAdsEnabled").orElse("false").get()
require(trachuAdsEnabled == "true" || trachuAdsEnabled == "false") {
    "trachuAdsEnabled must be true or false"
}
val trachuBannerId = providers.gradleProperty("trachuBannerId").orElse("").get().trim()
require(trachuBannerId.isEmpty() || Regex("R-M-[0-9]+-[0-9]+").matches(trachuBannerId)) {
    "trachuBannerId must be an RSYA banner ID (R-M-...-...)"
}
require(trachuAdsEnabled != "true" || trachuBannerId.isNotEmpty()) {
    "Enable release ads only after configuring this app's own trachuBannerId"
}

android {
    namespace = "ru.epbloger.trachu"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.epbloger.trachu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YANDEX_BANNER_ID", "\"\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") {
            // Yandex's official demo placement; never a production ad unit.
            buildConfigField("String", "YANDEX_BANNER_ID", "\"demo-banner-yandex\"")
        }
        getByName("release") {
            val activeId = if (trachuAdsEnabled == "true") trachuBannerId else ""
            buildConfigField("String", "YANDEX_BANNER_ID", "\"$activeId\"")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}
room {
    schemaDirectory("debug", "$projectDir/schemas/debug")
    schemaDirectory("release", "$projectDir/schemas/release")
    schemaDirectory("$projectDir/schemas")
}
dependencies {
    implementation("com.yandex.android:mobileads:8.7.0")
    implementation("com.yandex.android:mobileads-compose:8.7.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    kapt("androidx.room:room-compiler:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
