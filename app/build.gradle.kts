/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
                file("$rootDir/jctech.jks")
            storePassword = "he1755858138"
            keyAlias = "jctech"
            keyPassword = "he1755858138"
        }
        val properties = Properties()
        val localPropertyFile = project.rootProject.file("local.properties")
        if (localPropertyFile.canRead()) {
            properties.load(FileInputStream("$rootDir/local.properties"))
        }
        create("release") {
            storeFile =
                file("$rootDir/jctech.jks")
            keyAlias = "jctech"
            keyPassword = "he1755858138"
            storePassword = "he1755858138"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
             jvmTarget.set(JvmTarget.JVM_1_8)
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // 插件核心库 远程依赖方式
    //implementation(libs.combolite.core)

    // 插件核心库 本地依赖方式
    implementation(projects.core)
    implementation(projects.dependencies)
}
