

import com.combo.aar2apk.PackageBuildType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
//    alias(libs.plugins.aar2apk)
    id("io.github.lnzz123.combolite-aar2apk")
}

android {
    namespace = "com.combo.plugin.sample"
    defaultConfig {
        applicationId = "com.combo.plugin.sample"
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile =
                file("$rootDir/plugins/ecomap.jks")
            storePassword = "ani9772"
            keyAlias = "ecomap"
            keyPassword = "123456"
        }
        val properties = Properties()
        val localPropertyFile = project.rootProject.file("local.properties")
        if (localPropertyFile.canRead()) {
            properties.load(FileInputStream("$rootDir/local.properties"))
        }
        create("release") {
            storeFile =
                file("$rootDir/plugins/ecomap.jks")
            keyAlias = "ecomap"
            keyPassword = "123456"
            storePassword = "ani9772"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")

            packaging {
                resources {
                    excludes +=
                        listOf(
                            "DebugProbesKt.bin",
                            "kotlin-tooling-metadata.json",
                        )
                }
            }
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
             jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs =
                listOf(
                    "-Xno-param-assertions",
                    "-Xno-call-assertions",
                    "-Xno-receiver-assertions",
                )
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
}

packagePlugins {
    enabled.set(true)
    buildType.set(PackageBuildType.RELEASE)
    pluginsDir.set("debug_plugins")
}
configurations.all {
    exclude(group = "com.j256.ormlite", module = "ormlite-core")
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // 插件核心库 远程依赖方式
    //implementation(libs.combolite.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // 插件核心库 本地依赖方式
    implementation(libs.maplibre.opengl)
    implementation(libs.worldwind) {
        // ★ KMP 元数据把 Apple 专用的 ktor-client-darwin 泄漏成了通用依赖
        exclude(group = "io.ktor", module = "ktor-client-darwin")
    }
    // ★ Android 上替代 darwin 的 ktor 引擎（版本与 worldwind 依赖的 ktor 3.6.0 对齐）
    //implementation(libs.worldwind.ktor)
    implementation(libs.worldwind.assets)

    implementation(projects.core)
    implementation(project(":lib:lib-geokori"))
    compileOnly(project(":ui:ui-geokori"))
    implementation(projects.dependencies)
}
