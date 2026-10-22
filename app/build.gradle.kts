plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 显式 import：在 android{} 作用域内写 java.util.Properties() 时，
// 裸写的 java 会被 DSL 隐式接收者链解析冲突，导致 "Unresolved reference: util"
import java.util.Properties

android {
    namespace = "com.qbot.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.qbot.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 16
        versionName = "1.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名信息从 local.properties 读取，避免把密码写进版本库。
    // 密钥文件不存在时（典型如 CI 无 Secrets 场景）不配置签名：
    // release 产出未签名包、debug 回退默认调试密钥，保证构建不失败。
    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val keystoreFile = file(props.getProperty("STORE_FILE") ?: "qbot-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = props.getProperty("STORE_PASSWORD").orEmpty()
                keyAlias = props.getProperty("KEY_ALIAS").orEmpty()
                keyPassword = props.getProperty("KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            // 暂不开启混淆：序列化与 Room 的反射依赖较多，
            // 规则不全容易在运行时崩溃。规则已写在 proguard-rules.pro，
            // 需要瘦身时可自行开启。
            isMinifyEnabled = false
            isShrinkResources = false
            // 密钥存在才应用正式签名；否则产出未签名 release 包
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        debug {
            // debug 包也用同一把正式密钥签名：
            // 这样手机上 debug 与 release 之间可以直接覆盖安装，不会因签名冲突失败。
            // 密钥缺失时回退到 AGP 默认调试密钥。
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 协议层（纯 JVM，可复用）
    implementation(project(":botcore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 数据层
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 网络（botcore 已暴露为 api，此处显式声明以便统一版本）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
