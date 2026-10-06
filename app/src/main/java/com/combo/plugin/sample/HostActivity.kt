

package com.combo.plugin.sample

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.combo.core.component.activity.BaseHostActivity
import com.combo.core.runtime.PluginManager
import org.cwcc.open.geokori.permission.AppPermissionGate

class HostActivity : BaseHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (super.pluginActivity == null) {
            enableEdgeToEdge()
            setContent {
                AppPermissionGate() //请求授权

                val resources by PluginManager.resourcesManager.mResourcesFlow.collectAsState()
                key(resources) {
                    LoadingScreen()
                }
            }
        }
    }
}
