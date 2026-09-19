import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    alias(buildLibs.plugins.gradle.plugin.publish)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(gradleApi())
    implementation(buildLibs.android.gradle.api)
    implementation(buildLibs.kotlin.gradle.plugin)
}

gradlePlugin {
    group = "io.github.lnzz123"
    version = "1.1.1"
    website = "https://github.com/lnzz123/combolite"
    vcsUrl = "https://github.com/lnzz123/combolite.git"
    plugins {
        create("aarToApkPlugin") {
            id = "io.github.lnzz123.combolite-aar2apk"
            implementationClass = "com.combo.aar2apk.Aar2ApkPlugin"
            displayName = "AAR to APK Converter Plugin"
            tags = listOf("android", "aar", "apk")
            description = "A plugin to convert AAR files to APK files for plugin architecture"
        }
    }
}