plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish.vanniktech)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "org.cwcc.open.geokori.lib"

    defaultConfig {

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
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    //implementation(libs.androidx.foundation.layout)

    implementation(platform(libs.kotlin.bom))
    // 使用 BOM 管理 Compose 版本
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.timber)

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

    api(libs.photo.view)
    api(libs.glid)

    implementation(project(":core"))
    implementation(project(":ui:ui-geokori"))

    api(libs.coil.kt)
    api(libs.coil.okhttp)
    api(libs.coil.kt.compose)
    api(libs.coil.kt.svg)
    api(libs.coil.kt.gif)

    ///======sqlite数据库=======
    implementation(libs.ormLite)
    implementation(libs.xormlite)
    annotationProcessor(libs.xormAnnot)

    compileOnly(projects.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)

    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}