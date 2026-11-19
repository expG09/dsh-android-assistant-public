plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yanyan.assistant"
    compileSdk = 36
    // 本机只手动装了 build-tools 36.0.0；AGP 默认要 35.0.0 会找不到（sdkmanager 走不了代理）
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.yanyan.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.9.1"

        // M3b：sherpa-onnx 的 AAR 自带 4 个 ABI 的 .so（arm64 约 31 MB，其余 ABI 用不上）。
        // 真机是 arm64-v8a，只打这一份，APK 才不会白白大一百多兆。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // 与系统页大小对齐的常规打包方式；extractNativeLibs 保持默认（false，直接 mmap APK 里的 .so）
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    // M2 通道：WebSocket 客户端（含 HTTP POST /pair）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // M2 通道：token 存 EncryptedSharedPreferences（Keystore 后端）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // M3b：sherpa-onnx 官方 Android AAR（含 KWS / KeywordSpotter），来自
    // https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar
    // 官方不在 Maven Central 发布，这里用本地 AAR（app/libs）。
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
}
