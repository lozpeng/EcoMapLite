import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.kori.plugin.geo"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

dependencies {
    // =============================================================================================
    // 插件框架依赖（compileOnly —— 宿主提供，插件不含这些库的代码）
    //
    //  · lib-geokori：地理数据/网络层
    //  · dependencies：聚合模块，已 api 暴露 Compose / Lifecycle / Coroutines /
    //                  Material Icons / MapLibre SDK / Koin / Room / Retrofit / OkHttp 等
    //  · core：插件核心（PluginEntryClass 契约等）
    //  · plugins.common：插件公共工具
    //  · maplibre.opengl：显式声明（防止 dependencies 模块以后移除时静默失败）
    // =============================================================================================
    compileOnly(project(":lib:lib-geokori"))
    compileOnly(projects.dependencies)
    compileOnly(projects.core)
    compileOnly(projects.plugins.common)
    compileOnly(libs.maplibre.opengl)

    // =============================================================================================
    // 插件独占依赖（implementation —— 编译进插件 AAR）
    //
    // 只放 :combo:dependencies 没有暴露的库。
    // 已暴露的（Compose / Lifecycle / Coroutines / Material Icons / Koin / MapLibre 等）
    // 不加，否则会和宿主形成双份类，触发 ClassLoader 冲突。
    // =============================================================================================

    // ---- CameraX（视频录制）----
    // :combo:dependencies 只暴露了 maplibre.opengl，未暴露 CameraX，需要插件自带。
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation("androidx.camera:camera-core:${libs.versions.cameraVideo.get()}")
    implementation("androidx.camera:camera-camera2:${libs.versions.cameraVideo.get()}")
    implementation("androidx.camera:camera-lifecycle:${libs.versions.cameraVideo.get()}")

    // ---- Media3（音视频回放）----
    // 用于 MediaViewer 播放录制的 m4a / mp4。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // =============================================================================================
    // 测试
    // =============================================================================================
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}