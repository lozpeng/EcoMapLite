enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

includeBuild("build-logic")

pluginManagement {
    // Stage 1：看不到脚本主体里定义的函数，这里必须内联读取。
    // 路径用 rootDir 锚定，不依赖 CWD。
    val tomlFile = rootDir.resolve("gradle/libs.versions.toml")
    val agpVersion: String = run {
        check(tomlFile.exists()) { "libs.versions.toml not found at $tomlFile" }
        Regex("""^\s*agp\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(tomlFile.readText())?.groupValues?.get(1)
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

// Stage 2：这里定义的函数可以被 android { } / dependencyResolutionManagement { } 访问。
private val libsToml: String by lazy { rootDir.resolve("gradle/libs.versions.toml").readText() }

private fun tomlValue(key: String): String =
    Regex("""^\s*${Regex.escape(key)}\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(libsToml)?.groupValues?.get(1)
        ?: error("Version '$key' not found in libs.versions.toml")

private fun readTomlVersionInt(key: String): Int = tomlValue(key).toInt()

android {
    compileSdk { version = release(readTomlVersionInt("complySdk")) }
    minSdk     { version = release(readTomlVersionInt("minSdk")) }
    targetSdk  { version = release(readTomlVersionInt("complySdk")) }
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
//libs
include(":lib:lib-gdal")
include(":lib:lib-geokori")
include(":lib:lib-gps")
//ui
include(":ui:ui-geokori")


//plugins:base
include(":plugins:common")
include(":plugins:home")
include(":plugins:geokori")
include(":plugins:wildlife")

include(":plugins:guide")
include(":plugins:setting")
include(":plugins:example")