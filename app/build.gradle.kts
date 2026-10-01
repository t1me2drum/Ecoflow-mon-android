plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "app.powerhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.powerhub"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.8.1"
    }

    // CI signs with a persistent key (GitHub secrets) so new APKs install over old ones.
    // Without the env vars the standard local debug key is used.
    val ciKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val ciSigning = ciKeystore?.let {
        signingConfigs.create("ci") {
            storeFile = it
            storeType = "pkcs12"
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "powerhub"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (ciSigning != null) signingConfig = ciSigning
        }
        release {
            // Same persistent key as debug builds, so a release installs over an earlier dev build.
            // Locally (no CI key) it falls back to the debug key so assembleRelease still works.
            signingConfig = ciSigning ?: signingConfigs.getByName("debug")
            // Full protobuf runtime relies on reflection over generated classes,
            // so shrinking stays off to keep the build simple.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Protocol code is plain JVM; any stray Android call returns defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.29.3" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; the android.jar copy is a stub.
    testImplementation("org.json:json:20240303")
}
