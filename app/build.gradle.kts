plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.wakin.lada"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.wakin.lada"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2-preview"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir(rootProject.file("artifacts/app-assets"))
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val verifyModelAssets by tasks.registering {
    doLast {
        val assetFolder = rootProject.file("artifacts/app-assets")
        listOf("model-info.json", "detector.onnx", "detector-live.onnx", "restorer.onnx", "restorer-portable.onnx", "demo.mp4").forEach { name ->
            check(assetFolder.resolve(name).isFile) {
                "Missing app model asset: $name. Follow README model export steps and run scripts/prepare_app_assets.py."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyModelAssets) }
