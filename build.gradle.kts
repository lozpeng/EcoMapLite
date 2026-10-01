plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.aar2apk)
}

aar2apk {
    modules {
        module(":plugins:common")
        module(path = ":plugins:home")
        module(":plugins:guide")
        module(":plugins:example")
        module(":plugins:setting")
        module(":plugins:geokori")
        module(":plugins:wildlife")

    }
    // 配置签名信息

    // 测试签名
//    signing {
//        keystorePath.set(rootProject.file("test.jks").absolutePath)
//        keystorePassword.set("12345678")
//        keyAlias.set("test")
//        keyPassword.set("12345678")
//    }

    // 宿主签名
//    signing {
//        keystorePath.set(rootProject.file("jctech.jks").absolutePath)
//        keystorePassword.set("he1755858138")
//        keyAlias.set("jctech")
//        keyPassword.set("he1755858138")
//    }
    signing {
        keystorePath.set(rootProject.file("plugins/ecomap.jks").absolutePath)
        keystorePassword.set("ani9772")
        keyAlias.set("ecomap")
        keyPassword.set("123456")
    }
}
