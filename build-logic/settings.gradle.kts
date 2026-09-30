

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("buildLibs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "build-logic"
include(":aar2apk")
