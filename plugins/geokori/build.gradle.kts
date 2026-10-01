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
    implementation(platform(libs.androidx.compose.bom))

    compileOnly(project(":lib:lib-geokori"))
    compileOnly(projects.dependencies)
    compileOnly(libs.maplibre.opengl)
    // 插件核心库 本地依赖方式
    compileOnly(projects.core)
    compileOnly(projects.plugins.common)
}
