

package com.combo.aar2apk.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

@CacheableTask
abstract class PreparePluginAssetsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val targetDirName: Property<String>

    @Inject
    protected abstract fun getFileSystemOperations(): FileSystemOperations

    @TaskAction
    fun taskAction() {
        getFileSystemOperations().delete {
            delete(outputDir)
        }

        getFileSystemOperations().copy {
            from(sourceDir)
            into(outputDir.get().dir(targetDirName.get()))
        }
    }
}