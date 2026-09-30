plugins {
    id("com.android.application")
}

android {
    namespace = "com.babycam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.babycam"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    val media3Version = "1.11.1"
    // 1.19 requires compileSdk 37/AGP 9.1; 1.17 is the newest compatible line here.
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
