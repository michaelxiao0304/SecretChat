plugins {
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "chat.secretchat.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "chat.secretchat.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources.excludes += listOf("/libsignal_jni*.dylib", "/signal_jni*.dll", "/libsignal_jni*.so")
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}
dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("org.signal:libsignal-android:0.108.6")
    implementation("org.signal:libsignal-client:0.108.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}
