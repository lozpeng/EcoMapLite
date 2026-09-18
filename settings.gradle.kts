import java.io.File

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

includeBuild("build-logic")

pluginManagement {
    val agpVersion: String = run {
        val toml = File("gradle/libs.versions.toml")
        check(toml.exists()) { "libs.versions.toml not found" }
        Regex("""^\s*agp\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(toml.readText())?.groupValues?.get(1)
            ?: error("Cannot find 'agp' in libs.versions.toml")
    }

    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("com.android.settings") version agpVersion apply false
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.android.settings")
}

fun readTomlVersionInt(key: String): Int {
    val toml = File("gradle/libs.versions.toml")
    val value = Regex("""^\s*${Regex.escape(key)}\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(toml.readText())?.groupValues?.get(1)
        ?: error("Version '$key' not found in libs.versions.toml")
    return value.toInt()
}

android {
    compileSdk { version = release(readTomlVersionInt("complySdk")) }
    minSdk { version = release(readTomlVersionInt("minSdk")) }
    targetSdk { version = release(readTomlVersionInt("complySdk")) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "EcoMapLite"
include(":app")
include(":dependencies")
include(":core")
include(":lib:lib-gdal")
include(":lib:lib-geokori")
include(":lib:lib-gps")
include(":ui:ui-geokori")
include(":plugins:common")
include(":plugins:home")
include(":plugins:guide")
include(":plugins:setting")
include(":plugins:example")