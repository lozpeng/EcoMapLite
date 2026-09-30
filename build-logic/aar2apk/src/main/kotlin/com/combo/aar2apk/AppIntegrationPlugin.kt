

package com.combo.aar2apk

import com.android.build.api.variant.AndroidComponentsExtension
import com.combo.aar2apk.tasks.PreparePluginAssetsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.register

/**
 * 这是一个内部插件，专门用于将 aar2apk 插件的功能集成到 Android 应用模块中。
 */
class AppIntegrationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val extension =
                project.extensions.create("packagePlugins", PackagePluginsExtension::class.java)
            val androidComponents =
                project.extensions.getByType(AndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                if (extension.enabled.getOrElse(false)) {
                    val pluginBuildType = extension.buildType.get()
                    val buildPluginsTaskName = when (pluginBuildType) {
                        PackageBuildType.DEBUG -> "buildAllDebugPluginApks"
                        PackageBuildType.RELEASE -> "buildAllReleasePluginApks"
                    }

                    val rootBuildPluginsTask =
                        project.rootProject.tasks.findByName(buildPluginsTaskName)
                    if (rootBuildPluginsTask == null) {
                        project.logger.warn(
                            "警告：在根项目中未找到任务 '$buildPluginsTaskName'。请确保已在根项目 build.gradle.kts 中配置 aar2apk 插件并定义了要构建的模块。"
                        )
                        return@onVariants
                    }

                    val cleanTaskName = "clean${pluginBuildType.name.replaceFirstChar { it.uppercase() }}PluginApksOutput"
                    val outputDirToClean = project.rootProject.layout.buildDirectory.dir("outputs/plugin-apks/${pluginBuildType.name.lowercase()}")

                    val cleanTask = project.rootProject.tasks.findByName(cleanTaskName)
                        ?: project.rootProject.tasks.register<Delete>(cleanTaskName) {
                            group = "Plugin Packaging"
                            description = "清理旧的 [${pluginBuildType.name}] 插件 APKs 输出目录"
                            delete(outputDirToClean)
                        }.get()

                    rootBuildPluginsTask.dependsOn(cleanTask)

                    val variantCapitalized = variant.name.replaceFirstChar { it.uppercase() }
                    val taskName = "packagePluginsToIntermediateAssetsFor$variantCapitalized"

                    val packageTask = project.tasks.register<PreparePluginAssetsTask>(taskName) {
                        group = "Plugin Packaging"
                        description =
                            "将 [${pluginBuildType.name}] 插件 APKs 打包到 '$variantCapitalized' 构建的中间 aasets 目录"
                        dependsOn(rootBuildPluginsTask)

                        sourceDir.set(project.rootProject.layout.buildDirectory.dir("outputs/plugin-apks/${pluginBuildType.name.lowercase()}"))

                        outputDir.set(project.layout.buildDirectory.dir("intermediates/packaged_plugins_for_assets/${variant.name}"))

                        targetDirName.set(extension.pluginsDir)
                    }

                    variant.sources.assets?.addGeneratedSourceDirectory(
                        taskProvider = packageTask,
                        wiredWith = PreparePluginAssetsTask::outputDir
                    )
                }
            }
        }
    }
}