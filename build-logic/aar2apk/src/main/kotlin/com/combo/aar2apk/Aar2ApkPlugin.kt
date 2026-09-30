
package com.combo.aar2apk

import com.combo.aar2apk.internal.model.SdkInfo
import com.combo.aar2apk.internal.utils.SdkLocator
import com.combo.aar2apk.tasks.ConvertAarToApkTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.register

class Aar2ApkPlugin : Plugin<Project> {

    companion object {
        private const val GROUP_MAIN = "Plugin APKs"
        private const val GROUP_DEBUG_BUILDS = "Plugin APKs - Debug Builds"
        private const val GROUP_RELEASE_BUILDS = "Plugin APKs - Release Builds"
    }

    override fun apply(project: Project) {
        if (project == project.rootProject) {
            applyToRootProject(project)
        } else {
            project.plugins.withId("com.android.application") {
                project.plugins.apply(AppIntegrationPlugin::class.java)
            }
        }
    }

    /**
     * 插件被应用到根项目时的逻辑
     */
    private fun applyToRootProject(project: Project) {
        val extension = project.extensions.create("aar2apk", Aar2ApkExtension::class.java)

        project.afterEvaluate {
            if (extension.moduleConfigs.modules.isNotEmpty()) {
                extension.moduleConfigs.modules.forEach { config ->
                    project.evaluationDependsOn(config.path)
                }
                configurePluginBuildTasks(project, extension)
            } else {
                project.logger.info("Aar2ApkPlugin: 未在 aar2apk.modules 中配置任何模块，跳过任务创建。")
            }
        }
    }

    /**
     * 在根项目中配置所有插件构建任务
     */
    private fun configurePluginBuildTasks(project: Project, extension: Aar2ApkExtension) {
        val sdkPath = SdkLocator.getSdkPath(project)
        val buildToolsVersion = SdkLocator.findLatestBuildTools(sdkPath)
        val platformVersion = SdkLocator.findLatestPlatform(sdkPath)
        val sdkInfo = SdkInfo(sdkPath, buildToolsVersion, platformVersion)

        val pluginPackageIds = extension.moduleConfigs.modules.mapIndexed { index, config ->
            config.path to String.format("0x%02x", 0x80 + index)
        }.toMap()

        val debugTaskNames = mutableListOf<String>()
        val releaseTaskNames = mutableListOf<String>()

        extension.moduleConfigs.modules.forEach { options ->
            val modulePath = options.path
            val subproject = project.project(modulePath)
            val baseTaskName = modulePath.replace(":", "_").removePrefix("_")

            listOf("debug", "release").forEach { buildType ->
                val buildTypeCapitalized = buildType.replaceFirstChar { it.uppercase() }
                val taskName = "convert_${baseTaskName}_${buildType}"
                val assembleTaskName = "assemble$buildTypeCapitalized"
                val taskGroup =
                    if (buildType == "debug") GROUP_DEBUG_BUILDS else GROUP_RELEASE_BUILDS

                project.tasks.register<ConvertAarToApkTask>(taskName) {
                    group = taskGroup
                    dependsOn(subproject.tasks.named(assembleTaskName))

                    val aarFileName = "${subproject.name}-$buildType.aar"
                    aarFile.set(subproject.layout.buildDirectory.file("outputs/aar/$aarFileName"))
                    pluginName.set(subproject.name)
                    this.signingConfig.set(extension.signingConfig)
                    this.sdkInfo.set(sdkInfo)
                    this.buildType.set(buildType)
                    this.packageId.set(pluginPackageIds[modulePath]!!)
                    this.packagingOptions.set(options)

                    description =
                        "构建 ${subproject.name} 模块并转换为 $buildTypeCapitalized 插件APK (精细化配置)"

                    val config = subproject.configurations.getByName("${buildType}RuntimeClasspath")

                    if (options.isAnyDependencyIncluded()) {
                        remoteDependencyAars.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "aar"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesRes.get()) {
                        localDependencyResDirs.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-res"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesDex.get()) {
                        localDependencyClasses.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-classes-jar"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesAssets.get()) {
                        localDependencyAssets.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-assets"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesJni.get()) {
                        localDependencyJniLibs.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-jni"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    outputDirectory.set(project.layout.buildDirectory.dir("outputs/plugin-apks/$buildType"))
                }
                if (buildType == "debug") {
                    debugTaskNames.add(taskName)
                } else {
                    releaseTaskNames.add(taskName)
                }
            }
        }

        project.tasks.register("buildAllDebugPluginApks") {
            group = GROUP_MAIN
            description = "一键构建所有已配置模块的Debug插件APK"
            dependsOn(debugTaskNames)
            outputs.upToDateWhen { false }
        }
        project.tasks.register("buildAllReleasePluginApks") {
            group = GROUP_MAIN
            description = "一键构建所有已配置模块的Release插件APK"
            dependsOn(releaseTaskNames)
            outputs.upToDateWhen { false }
        }

        project.tasks.register<Delete>("cleanAllPluginApks") {
            group = GROUP_MAIN
            description = "清理所有生成的插件APK、相关日志和临时工作目录"
            delete(
                project.layout.buildDirectory.dir("outputs/plugin-apks"),
                project.layout.buildDirectory.dir("logs/aar2apk")
            )
            doLast {
                val tmpDir = project.layout.buildDirectory.get().asFile.resolve("tmp")
                if (tmpDir.exists() && tmpDir.isDirectory) {
                    tmpDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("convert_") }
                        ?.forEach {
                            project.delete(it)
                            project.logger.lifecycle("Deleted temporary directory: ${it.path}")
                        }
                }
            }
        }
    }
}