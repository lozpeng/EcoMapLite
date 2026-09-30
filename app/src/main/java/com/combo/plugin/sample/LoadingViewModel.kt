

package com.combo.plugin.sample

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.combo.core.api.IPluginEntryClass
import com.combo.core.runtime.PluginManager
import com.combo.core.utils.installPluginsFromAssetsForDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream


/**
 * 插件状态枚举
 */
enum class PluginStatus {
    /** 插件未安装 */
    NOT_INSTALLED,

    /** 插件已安装但未启动 */
    INSTALLED_NOT_STARTED,

    /** 插件已安装且已启动 */
    INSTALLED_AND_STARTED,
}


class LoadingViewModel(
    context: Context,
) : ViewModel() {
    @SuppressLint("StaticFieldLeak")
    private val context = context.applicationContext

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _entryClass = MutableStateFlow<IPluginEntryClass?>(null)
    val entryClass: StateFlow<IPluginEntryClass?> = _entryClass.asStateFlow()

    companion object {
        const val BASE_PATH = "plugins"
        const val PLUGIN_COMMON = "com.combo.plugin.sample.common"
        const val PLUGIN_HOME = "com.combo.plugin.sample.home"
    }

    init {
        // ViewModel 初始化时，只做一件事：等待框架就绪并开始观察插件状态
        viewModelScope.launch {
            PluginManager.awaitInitialization()
            Timber.d("ViewModel 检测到框架已就绪，开始观察插件实例。")

            PluginManager.pluginInstancesFlow
                .onEach { loadedPlugins ->
                    val homePlugin = loadedPlugins[PLUGIN_HOME]
                    _entryClass.value = homePlugin
                    if (homePlugin != null) {
                        _loading.value = false
                    }
                }
                .launchIn(viewModelScope)
        }
        setupPlugins()
    }

    fun setupPlugins() {
        viewModelScope.launch {
            PluginManager.awaitInitialization()

            if (BuildConfig.DEBUG) {
                Timber.d("ViewModel 开始执行Debug模式下的插件安装...")
                context.installPluginsFromAssetsForDebug(assetsDirName = "debug_plugins")
            }

            Timber.d("ViewModel 正在加载所有已启用插件...")
            PluginManager.loadEnabledPlugins()

            if (_entryClass.value == null) {
                _loading.value = false
            }
        }
    }

    fun setLoading(isLoading: Boolean) {
        _loading.value = isLoading
    }

    /**
     * (release 模式) 用户点击按钮后安装插件
     */
    fun installPlugin(
        assetPath: String,
        forceOverwrite: Boolean = false,
    ) {
        viewModelScope.launch {
            setLoading(true)
            val pluginFiles = context.assets.list(assetPath)
            pluginFiles?.forEach { fileName ->
                val pluginFile = File(context.filesDir, fileName)
                context.assets.open("$assetPath/$fileName").use { inputStream ->
                    FileOutputStream(pluginFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                PluginManager.installerManager.installPlugin(pluginFile, forceOverwrite)
            }
            PluginManager.loadEnabledPlugins()
        }
    }

    /**
     * (release 模式) 用户点击按钮后启动插件
     */
    fun launchBasePlugin() {
        viewModelScope.launch {
            setLoading(true)
            PluginManager.launchPlugin(PLUGIN_COMMON)
            PluginManager.launchPlugin(PLUGIN_HOME)
            // 无需手动更新状态，Flow观察者会自动处理
        }
    }

    /**
     * 获取指定插件的状态
     */
    fun getPluginStatus(pluginId: String): PluginStatus {
        val isInstalled = PluginManager.getAllInstallPlugins().any { it.id == pluginId }
        if (!isInstalled) {
            return PluginStatus.NOT_INSTALLED
        }

        val entryClass = PluginManager.pluginInstancesFlow.value[pluginId]
        return if (entryClass != null) {
            PluginStatus.INSTALLED_AND_STARTED
        } else {
            PluginStatus.INSTALLED_NOT_STARTED
        }
    }
}