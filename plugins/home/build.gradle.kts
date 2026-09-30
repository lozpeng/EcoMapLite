import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.combo.plugin.sample.home"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 为未使用的资源生成R类
            isShrinkResources = false
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    compileOnly(projects.dependencies)
    implementation(project(":ui:ui-geokori"))
    // 插件核心库 远程依赖方式
//    compileOnly(libs.combolite.core)
    // 插件核心库 本地依赖方式
    compileOnly(projects.core)
    compileOnly(projects.plugins.common)
    compileOnly(projects.plugins.guide)
    compileOnly(projects.plugins.example)
    compileOnly(projects.plugins.setting)

}
