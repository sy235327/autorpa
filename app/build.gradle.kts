import java.util.Properties
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.firerpa"
    compileSdk = 34

    defaultConfig {
        // [fork] 与原版（com.firerpa）共存分发；AutoRPA 正式包名（首发后不再变更，否则用户无法覆盖安装）
        applicationId = "com.autorpa.console"
        minSdk = 24
        targetSdk = 34

        val getVersionName = {
            try {
                val stdout = ByteArrayOutputStream()
                project.exec {
                    commandLine("git", "describe", "--tags", "--always")
                    standardOutput = stdout
                }
                stdout.toString().trim().removePrefix("v")
            } catch (e: Exception) {
                "1.0.0"
            }
        }

        val computeVersionCode: (String) -> Int = { versionNameStr ->
            try {
                val cleanVersion = versionNameStr.split("-")[0]
                val parts = cleanVersion.split(".")

                val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

                major * 10000 + minor * 100 + patch
            } catch (e: Exception) {
                1
            }
        }

        val currentVersionName = getVersionName()
        versionName = currentVersionName
        versionCode = computeVersionCode(currentVersionName)
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProperties.getProperty("storeFile")
                    ?: error("storeFile is missing in keystore.properties"),
            )
            storePassword = keystoreProperties.getProperty("storePassword")
                ?: error("storePassword is missing in keystore.properties")
            keyAlias = keystoreProperties.getProperty("keyAlias")
                ?: error("keyAlias is missing in keystore.properties")
            keyPassword = keystoreProperties.getProperty("keyPassword")
                ?: error("keyPassword is missing in keystore.properties")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

// `build` 默认会跑 connectedAndroidTest，无设备/ADB 异常时会一直等
tasks.matching {
    it.name.startsWith("connected") && it.name.endsWith("AndroidTest")
}.configureEach {
    enabled = false
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("org.ini4j:ini4j:0.5.4")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
