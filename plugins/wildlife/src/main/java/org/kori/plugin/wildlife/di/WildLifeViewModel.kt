package org.cwcc.open.plugin.wildlife.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.combo.plugin.sample.common.viewmodel.BaseViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kori.plugin.wildlife.di.WildLifeState
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("StaticFieldLeak")
class WildLifeViewModel(
    private val context: Context
) : BaseViewModel<WildLifeState>(
    initialState = WildLifeState()
) {

    init {
    }

    fun refreshWildLifeData() {
        viewModelScope.launch {
            updateState {
                copy(
                    isLoading = true,
                )
            }
            //updateInstalledPlugins()
            //添加在线刷新数据
            delay(1000.milliseconds)
            updateState {
                copy(
                    isLoading = false,
                )
            }
        }
    }
}
