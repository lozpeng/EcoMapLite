# --- 核心 API 与基类 ---
# 保留所有公开的 API 接口和四大组件的基类，确保插件可以正常实现和继承。
-keep public interface com.combo.core.api.** { *; }
-keep public class com.combo.core.component.**.* { *; }
-keep public class com.combo.core.runtime.app.BaseHostApplication { *; }

# --- 核心管理器 ---
# PluginManager 是框架的唯一入口，必须完整保留。
-keep public class com.combo.core.runtime.PluginManager { *; }

# --- 数据模型 ---
# 保留所有数据模型类，防止序列化和 Parcelable 操作失败。
-keep class com.combo.core.model.** { *; }
-keep @kotlinx.serialization.Serializable class * {
    <fields>;
    *** Companion;
}
-keep class *$$serializer { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# --- 枚举类 ---
# 保留所有枚举类的必要方法。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.combo.core.runtime.ValidationStrategy { *; }


# --- 权限与安全系统 ---
# 保留自定义的注解，确保它在运行时可见。
-keep @interface com.combo.core.security.permission.RequiresPermission { *; }
# 保留所有被 @RequiresPermission 注解标记的方法。这可以防止它们被重命名或移除，
-keepclassmembers class * {
    @com.combo.core.security.permission.RequiresPermission <methods>;
}


# --- Kotlin 反射元数据保护 ---
# 保护 Kotlin 的元数据注解。R8 在处理 Kotlin 反射时需要这些信息。
-keep class kotlin.Metadata { *; }
-keep @kotlin.Metadata class * {
    <fields>;
    <methods>;
}

# --- 崩溃与授权界面 ---
# 保留用于显示UI的 Activity 类。
-keep public class com.combo.core.security.crash.CrashActivity { *; }
-keep public class com.combo.core.security.auth.AuthorizationActivity { *; }

# --- 其他保留规则 ---
# 保留公共扩展函数
-keep public class com.combo.core.utils.ExtensionsKt {
    public static <methods>;
}
# 保留 Jetpack Compose 相关规则
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class **.R$* {
    <fields>;
}
# 保留 Koin 依赖注入的相关规则
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module
-keep class * implements org.koin.core.module.Module
-keep class org.koin.ksp.generated.** { *; }
-keep,includedescriptorclasses class **.*_Factory { *; }