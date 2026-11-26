import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 版本号按「日期.当天第几次打包」生成，例如 20261124.001；每次打包自动递增并写回 version.properties
val versionFile = rootProject.file("version.properties")
val buildDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
val storedVersion = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}
val buildSeq = if (storedVersion.getProperty("date") == buildDay) {
    (storedVersion.getProperty("count")?.toIntOrNull() ?: 0) + 1
} else 1
val appVersionName = "$buildDay.${String.format(Locale.US, "%03d", buildSeq)}"

fun persistBuildVersion() {
    Properties().apply {
        setProperty("date", buildDay)
        setProperty("count", buildSeq.toString())
    }.store(versionFile.outputStream(), null)
}

// 只在真正执行 APK/AAB 打包任务后写回编号；IDE 同步、单元测试、clean、只编译都不会消耗编号。
// 不能按任务图里是否含 assemble 判断：testDebugUnitTest 等也会拉入 assembleDebugUnitTest 而被误判。
tasks.configureEach {
    if (name.matches(Regex("^(package|bundle)(Debug|Release)$"))) {
        doLast { persistBuildVersion() }
    }
}

android {
    namespace = "com.photosync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.photosync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = appVersionName
    }

    // 统一签名：使用项目内置的 debug.keystore（默认密码 android），
    // 保证任意电脑打包出的 debug 包签名一致，可直接覆盖安装。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
