plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.riftwardechoprotocol.k7m4x9"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.riftwardechoprotocol.k7m4x9"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("wav") }
    sourceSets.getByName("main") {
        assets.srcDir(rootProject.file("graphics"))
        res.srcDir(rootProject.file("app-icons"))
    }
}
