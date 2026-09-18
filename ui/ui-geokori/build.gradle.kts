plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish.vanniktech)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "org.cwcc.open.geokori.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 25

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    //implementation(libs.androidx.foundation.layout)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.kotlin.bom))
    // 使用 BOM 管理 Compose 版本
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.serialization.json)
    // Used in the public API
    api(libs.kotlinx.datetime)

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation.layout)

    implementation(platform(libs.retrofit.bom))
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    api(libs.photo.view)
    api(libs.glid)

    implementation(project(":core"))

    api(libs.coil.kt)
    api(libs.coil.okhttp)
    api(libs.coil.kt.compose)
    api(libs.coil.kt.svg)
    api(libs.coil.kt.gif)

    implementation(libs.gsyVideoPlayer.java) {
        // 比如，如果你想排除它自带的 videocache，转而使用另一个版本
        exclude(group = "com.github.CarGuo.GSYVideoPlayer", module = "gsyvideoplayer-androidvideocache")
    }
    // 根据 CPU 架构选择（至少选一个）
    api(libs.gsyVideoPlayer.arm64)  // 现代手机基本都是 arm64
    // 如果需要支持 armeabi-v7a 设备
    //implementation(libs.gsyVideoPlayer.armv7a)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)

    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}