// ============================================================================
// 本地视频压缩器 - 应用模块构建脚本
//
// 技术栈：Kotlin + Jetpack Compose + Media3 Transformer + Room + DataStore
// 目标：完全本地运行，不联网、不上传、不需要账号
// ============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.videocompress.local"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videocompress.local"
        minSdk = 26          // Android 8.0，覆盖绝大多数在用的 VIVO 机型
        targetSdk = 36       // Android 16，满足 mediaProcessing 前台服务规范
        // 明确指定本机已安装的 build-tools，避免构建时再去联网下载
        buildToolsVersion = "36.0.0"
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 发布签名：使用仓库内自带的自签名 keystore，保证同一台设备可覆盖升级安装
    signingConfigs {
        create("release") {
            storeFile = file("keystore/videocompress.jks")
            storePassword = "videocompress"
            keyAlias = "videocompress"
            keyPassword = "videocompress"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module"
            )
        }
    }
}

// Room 导出数据库 schema，便于后续版本升级排查
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ------------------------------------------------------------------
    // Jetpack Compose（BOM 统一管理版本）
    // ------------------------------------------------------------------
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ------------------------------------------------------------------
    // 基础组件
    // ------------------------------------------------------------------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ------------------------------------------------------------------
    // 媒体压缩引擎：Media3 Transformer（硬件编码，无需 FFmpeg）
    // ------------------------------------------------------------------
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-muxer:1.5.1")

    // ------------------------------------------------------------------
    // 持久化：Room（任务队列 + 日志）
    // ------------------------------------------------------------------
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // ------------------------------------------------------------------
    // 设置存储：DataStore
    // ------------------------------------------------------------------
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
