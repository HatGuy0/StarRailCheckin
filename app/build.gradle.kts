import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 签名凭证从 local.properties 读取（该文件已被忽略，不入库）
val keystoreProps = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mh.starrailcheckin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mh.starrailcheckin"
        minSdk = 26
        targetSdk = 36
        versionCode = 71
        versionName = "2.21"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = keystoreProps.getProperty("STORE_PASSWORD", "")
            keyAlias = keystoreProps.getProperty("KEY_ALIAS", "")
            keyPassword = keystoreProps.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Kyant0 backdrop 液态玻璃库（android 分支 1.0.6 配套 shapes 1.2.0）
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")

    // Miuix 组件库（偏好条目 / 弹窗等）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.0")
    // Miuix 偏好条目组件（ArrowPreference / OverlaySpinnerPreference / RadioButtonPreference 等）
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.0")

    // Miuix OverlayDialog 预测式返回手势依赖（AndroidX 官方坐标；1.0.1 为 Miuix 0.9.0 配套版本）
    implementation("androidx.navigationevent:navigationevent-android:1.0.1")
    implementation("androidx.navigationevent:navigationevent-compose-android:1.0.1")

    // 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager 后台周期任务（打卡校验 / 自动签到 / Cookie 检查 / 体力监控）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 二维码生成（扫码登录）
    implementation("com.google.zxing:core:3.5.3")

    // 图片裁剪（头像 / 卡片背景）
    implementation("com.github.yalantis:ucrop:2.2.9")
}
