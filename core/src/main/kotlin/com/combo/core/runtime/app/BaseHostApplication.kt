

package com.combo.core.runtime.app

import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import com.combo.core.runtime.PluginManager
import com.combo.core.security.crash.PluginCrashHandler

/**
 * 宿主端的插件框架Application基类
 * 用于快速一键初始化插件框架，加载插件
 */
open class BaseHostApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PluginCrashHandler.initialize(this)

        PluginManager.initialize(
            context = this,
            onSetup = onFrameworkSetup()
        )
    }

    /**
     * 重写getResources方法，以提供合并后的插件资源。
     */
    override fun getResources(): Resources =
        if (PluginManager.isInitialized) {
            PluginManager.resourcesManager.getResources()
        } else {
            super.getResources()
        }

    /**
     * 重写getAssets方法，以提供合并后的插件资源。
     */
    override fun getAssets(): AssetManager =
        if (PluginManager.isInitialized) {
            PluginManager.resourcesManager.getResources().assets
        } else {
            super.getAssets()
        }

    /**
     * 重写此方法以提供自定义的插件框架设置逻辑。
     */
    protected open fun onFrameworkSetup(): suspend () -> Unit {
        return {  }
    }
}