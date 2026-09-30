

package com.combo.plugin.sample

import com.combo.core.model.PluginCrashInfo
import com.combo.core.runtime.PluginManager
import com.combo.core.runtime.PluginManager.setValidationStrategy
import com.combo.core.runtime.ValidationStrategy
import com.combo.core.runtime.app.BaseHostApplication
import com.combo.core.security.crash.IPluginCrashCallback
import com.combo.core.security.crash.PluginCrashHandler
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

/**
 * 主应用程序类
 * 该类是主应用程序的入口点，负责初始化插件框架和配置应用程序的全局状态
 */
class HostApp : BaseHostApplication(), IPluginCrashCallback {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        loadKoinModules(
            module {
                viewModel { LoadingViewModel(applicationContext) }
            },
        )
    }

    /**
     * 重写此方法以提供自定义的插件框架设置逻辑。
     */
    override fun onFrameworkSetup(): suspend () -> Unit {
        return {
            PluginManager.proxyManager.apply {
                setHostActivity(HostActivity::class.java)
                setServicePool(
                    listOf(
                        HostService1::class.java,
                        HostService2::class.java,
                        HostService3::class.java,
                        HostService4::class.java,
                        HostService5::class.java,
                        HostService6::class.java,
                        HostService7::class.java,
                        HostService8::class.java,
                        HostService9::class.java,
                        HostService10::class.java,
                    ),
                )
                setHostProviderAuthority("com.combo.plugin.sample.provider")
            }

            setValidationStrategy(ValidationStrategy.UserGrant)
            PluginCrashHandler.setGlobalClashCallback(this@HostApp)
        }
    }

    // --- IPluginCrashCallback 实现 ---
    override fun onClassCastException(info: PluginCrashInfo): Boolean {
        Timber.e(info.throwable, "插件ClassCastException ${info.culpritPluginId}")
        return false
    }

    override fun onDependencyException(info: PluginCrashInfo): Boolean {
        Timber.e(info.throwable, "插件PluginDependencyException ${info.culpritPluginId}")
        return false
    }

    override fun onResourceNotFoundException(info: PluginCrashInfo): Boolean {
        Timber.e(info.throwable, "插件Resources.NotFoundException ${info.culpritPluginId}")
        return false
    }

    override fun onApiIncompatibleException(info: PluginCrashInfo): Boolean {
        Timber.e(info.throwable, "插件onApiIncompatibleException ${info.culpritPluginId}")
        return false
    }

    override fun onOtherPluginException(info: PluginCrashInfo): Boolean {
        Timber.e(info.throwable, "插件onOtherPluginException ${info.culpritPluginId}")
        return false
    }
}