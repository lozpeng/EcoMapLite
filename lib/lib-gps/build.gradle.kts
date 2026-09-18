plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.android.gpstest.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }

    lint {
        disable += setOf(
                "JvmStaticProvidesInObjectDetector",
                "FieldSiteTargetOnQualifierAnnotation",
                "ModuleCompanionObjects",
                "ModuleCompanionObjectsNotInModuleParent",
                "StringFormatInvalid",
                "MissingTranslation",
                "ExtraTranslation",
                "ExpiredTargetSdkVersion"
        )
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        // jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)

    // Compose 导航
    implementation(libs.androidx.navigation.compose)

    // Activity Compose
    implementation(libs.androidx.activity.compose)

    // 调试 Compose
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 协程
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutines.android)

    // Koin 依赖注入
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // JSON 处理
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.4")

    // MPAndroidChart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Sliding drawer
    implementation("com.sothree.slidinguppanel:library:3.4.0")

    // QR Code reader
    implementation("com.google.zxing:android-integration:3.3.0")

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Timber 日志
    implementation(libs.timber)

    // Coil 图片加载
    implementation(libs.coil.kt)
    implementation(libs.coil.kt.compose)

    // Lottie 动画
    implementation(libs.lottie)

    // Landscapist
    implementation(libs.landscapist.glide)
    implementation(libs.landscapist.animation)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.immutable.collection)
}